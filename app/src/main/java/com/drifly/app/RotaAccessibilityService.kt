package com.drifly.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RotaAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isProcessingTree = AtomicBoolean(false)

    private var lastOfferHash: Int = 0
    private var lastOfferTimestamp: Long = 0
    private var lastScanTimestamp: Long = 0
    private var lastReceiptHash: Int = 0
    private var lastReceiptTimestamp: Long = 0
    private var lastHandledRideId: Long = -1L
    private var lastReceiptProcessedTimestamp: Long = 0L
    private var lastScreenshotTimestamp: Long = 0L
    private var declineCooldownUntil: Long = 0L
    private var lastDiagEventTimestamp: Long = 0L
    private var activeRideStartTime: Long = 0L
    private var lastAcceptedTimestamp: Long = 0L

    // Vigia de OCR: a 99 desenha o card em canvas e emite pouquíssimos eventos de acessibilidade,
    // então não dá para depender de evento para capturar a oferta. Este watchdog tenta o OCR
    // periodicamente enquanto o app monitorado está na tela e a árvore veio sem nenhum texto.
    private val ocrHandler = Handler(Looper.getMainLooper())
    private var consecutiveEmptyOcrCount = 0

    @Volatile
    private var ocrFastRetryPending = false

    private lateinit var prefs: PreferencesManager
    private lateinit var db: DatabaseHelper

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        prefs = PreferencesManager(this)
        db = DatabaseHelper(this)
        try {
            Log.i(TAG, "RotaAccessibilityService inicializado com sucesso.")
        } catch (ignored: Throwable) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.init(this)
        ocrHandler.removeCallbacks(ocrWatchdogRunnable)
        ocrHandler.postDelayed(ocrWatchdogRunnable, OCR_WATCHDOG_INTERVAL_MS)
        diag("WATCHDOG iniciado")
    }

    /**
     * Vigia periódico de OCR.
     *
     * Por que existe: a 99 renderiza o card de oferta em canvas Flutter, sem nenhum texto na árvore
     * de acessibilidade, e dispara pouquíssimos eventos. Depender de evento significava UMA tentativa
     * de captura por oferta — e se essa única captura caísse no meio da transição de tela (frame
     * preto), a oferta passava batida e o overlay nunca aparecia.
     */
    private val ocrWatchdogRunnable = object : Runnable {
        override fun run() {
            var nextDelay = OCR_WATCHDOG_INTERVAL_MS
            try {
                val started = runOcrWatchdogTick()
                if (started && ocrFastRetryPending && consecutiveEmptyOcrCount < OCR_MAX_FAST_RETRIES) {
                    nextDelay = OCR_FAST_RETRY_MS
                } else {
                    ocrFastRetryPending = false
                }
            } catch (t: Throwable) {
                try { Log.w(TAG, "Falha no watchdog de OCR", t) } catch (ignored: Throwable) {}
            } finally {
                ocrHandler.removeCallbacks(this)
                ocrHandler.postDelayed(this, nextDelay)
            }
        }
    }

    /** @return true se disparou uma captura de tela + OCR nesta passagem. */
    private fun runOcrWatchdogTick(): Boolean {
        if (!prefs.isMonitoringActive) return false
        if (isScreenUnsafeForCapture()) return false
        if (System.currentTimeMillis() < declineCooldownUntil) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val now = System.currentTimeMillis()
        if (now - lastScreenshotTimestamp < OCR_MIN_INTERVAL_MS) return false

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val activePkg = try { root.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        if (activePkg.contains("com.drifly.app", ignoreCase = true)) return false
        if (!isSupportedPackageName(activePkg)) return false

        // Se a árvore tem texto, o caminho normal de leitura já resolve (Uber, iFood...)
        val texts = ArrayList<String>()
        val clickables = ArrayList<AccessibilityNodeInfo>()
        try { traverseNodes(root, texts, clickables, 0) } catch (_: Exception) {}
        if (texts.isNotEmpty()) {
            ocrFastRetryPending = false
            return false
        }

        diag("WATCHDOG OCR pkg=$activePkg")
        performOcrFallback(activePkg, clickables)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.isMonitoringActive) return

        val packageName = event.packageName?.toString() ?: return
        // Ignora completamente qualquer evento vindo do próprio DriFly ou enquanto o DriFly está na frente
        if (packageName.contains("com.drifly.app", ignoreCase = true)) return
        if (!isSupportedPackageName(packageName)) return
        if (isOwnAppForeground()) return

        val now = System.currentTimeMillis()

        // DIAGNÓSTICO: registra cada evento recebido dos apps monitorados (limitado a 1 por segundo)
        if (now - lastDiagEventTimestamp > 1000L) {
            lastDiagEventTimestamp = now
            diag("EVT pkg=$packageName type=${AccessibilityEvent.eventTypeToString(event.eventType)} className=${event.className}")
        }

        // 0. Detecção de Clique Manual em Aceitar ou Recusar Corrida
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleManualClickEvent(event, now)
            return
        }

        // Se o motorista acabou de recusar uma corrida, aguarda o card sumir completamente (evita ler restos da tela)
        if (now < declineCooldownUntil) return

        // 1. Processamento de Notificações de Corrida (quando o app toca em segundo plano ou tela dividida)
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notifTexts = ArrayList<String>()
            event.text?.forEach { charSeq ->
                if (!charSeq.isNullOrBlank()) notifTexts.add(charSeq.toString())
            }
            val notif = event.parcelableData as? android.app.Notification
            if (notif != null && notif.extras != null) {
                val t = notif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                val txt = notif.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                val bt = notif.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
                val st = notif.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)?.toString()
                if (!t.isNullOrBlank() && !notifTexts.contains(t)) notifTexts.add(t)
                if (!txt.isNullOrBlank() && !notifTexts.contains(txt)) notifTexts.add(txt)
                if (!bt.isNullOrBlank() && !notifTexts.contains(bt)) notifTexts.add(bt)
                if (!st.isNullOrBlank() && !notifTexts.contains(st)) notifTexts.add(st)
            }
            if (notifTexts.isNotEmpty()) {
                val offer = ParsingEngine.parseNodes(
                    packageName = packageName,
                    texts = notifTexts,
                    costPerKm = prefs.costPerKm,
                    minRatingThreshold = prefs.minPassengerRating
                )
                if (offer != null) {
                    processDetectedOffer(offer, emptyList(), now)
                    return
                }
            }
            return
        }

        // Throttle rigoroso na Main Thread para impedir ANR (Android Not Responding) durante navegação GPS
        if (now - lastScanTimestamp < 320L) return
        if (!isProcessingTree.compareAndSet(false, true)) return
        lastScanTimestamp = now

        // CRÍTICO: o sistema RECICLA o AccessibilityEvent assim que onAccessibilityEvent retorna.
        // Ler event.eventType / event.source dentro da corrotina devolve valores zerados (type=0, source=null).
        // Por isso capturamos tipo e origem aqui, de forma síncrona, antes de ir para a thread de trabalho.
        val eventType = event.eventType
        val eventSource = try { event.source } catch (_: Exception) { null }

        serviceScope.launch {
            try {
                // Coleta raízes em background sem travar a UI thread do sistema
                val candidateRoots = ArrayList<AccessibilityNodeInfo>()
                try {
                    eventSource?.let { src ->
                        val pkg = src.packageName?.toString() ?: ""
                        if (!pkg.contains("com.drifly.app", ignoreCase = true)) {
                            candidateRoots.add(src)
                        }
                    }
                } catch (_: Exception) {}

                try {
                    rootInActiveWindow?.let { activeRoot ->
                        val pkg = activeRoot.packageName?.toString() ?: ""
                        if (!pkg.contains("com.drifly.app", ignoreCase = true) && !candidateRoots.contains(activeRoot)) {
                            candidateRoots.add(activeRoot)
                        }
                    }
                } catch (_: Exception) {}

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && candidateRoots.isEmpty()) {
                        windows?.forEach { win ->
                            val wRoot = win.root
                            if (wRoot != null && !candidateRoots.contains(wRoot)) {
                                val wPkg = wRoot.packageName?.toString() ?: ""
                                if (isSupportedPackageName(wPkg) && !wPkg.contains("com.drifly.app", ignoreCase = true)) {
                                    candidateRoots.add(wRoot)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao inspecionar janelas", e)
                }

                if (candidateRoots.isEmpty()) return@launch

                // Escala até o topo de cada raiz encontrada (com limite de profundidade para evitar loops)
                val topRoots = ArrayList<AccessibilityNodeInfo>()
                for (node in candidateRoots) {
                    var curr = node
                    var depth = 0
                    var parent = curr.parent
                    while (parent != null && depth < 25) {
                        curr = parent
                        parent = curr.parent
                        depth++
                    }
                    val rootPkg = curr.packageName?.toString() ?: ""
                    if (!rootPkg.contains("com.drifly.app", ignoreCase = true) && !topRoots.contains(curr)) {
                        topRoots.add(curr)
                    }
                }

                val extractedTexts = ArrayList<String>()
                val clickableNodes = ArrayList<AccessibilityNodeInfo>()

                for (topRoot in topRoots) {
                    traverseNodes(topRoot, extractedTexts, clickableNodes, 0)
                }

                diag("TREE pkg=$packageName roots=${topRoots.size} texts=${extractedTexts.size} click=${clickableNodes.size} rideActive=$isRideCurrentlyActive")
                if (extractedTexts.isNotEmpty()) {
                    diag("TEXTS pkg=$packageName :: ${extractedTexts.joinToString(" | ").take(400)}")
                }

                if (extractedTexts.isNotEmpty()) {
                    // Reseta corrida ativa se o motorista voltou para tela ociosa (online procurando corridas)
                    if (isRideCurrentlyActive && ParsingEngine.isIdleDriverScreen(extractedTexts)) {
                        diag("BRANCH IDLE_SCREEN -> motorista online aguardando chamadas, limpa status de corrida ativa")
                        isRideCurrentlyActive = false
                    }

                    // Se a corrida ativa ficou sem eventos de navegação por mais de 45 min, expira o bloqueio
                    if (isRideCurrentlyActive && (now - activeRideStartTime > 45 * 60 * 1000L)) {
                        diag("ACTIVE_RIDE expirada por timeout (45 min)")
                        isRideCurrentlyActive = false
                    }

                    // Classificação em ordem segura:
                    // recibo/cancelamento -> nova oferta -> tela de corrida ativa.
                    // (O recibo precisa vir primeiro: ver comentário em ScreenRouter.)
                    when (val reading = ScreenRouter.read(
                        packageName = packageName,
                        texts = extractedTexts,
                        costPerKm = prefs.costPerKm,
                        minRatingThreshold = prefs.minPassengerRating,
                        isRideCurrentlyActive = isRideCurrentlyActive
                    )) {
                        is ScreenReading.Receipt -> {
                            val receipt = reading.receipt
                            diag("BRANCH RECEIPT ${receipt.platform} completed=${receipt.isCompleted} cancelled=${receipt.isCancelled}")
                            val currentReceiptHash = receipt.hashCode()
                            if (currentReceiptHash != lastReceiptHash && (now - lastReceiptProcessedTimestamp) > 8_000L) {
                                lastReceiptHash = currentReceiptHash
                                lastReceiptTimestamp = now
                                handleReceipt(receipt)
                            }
                        }

                        is ScreenReading.Offer -> {
                            val offer = reading.offer
                            diag("BRANCH OFFER_OK ${offer.platform} R$ ${offer.price} ${offer.distanceKm}km")
                            processDetectedOffer(offer, clickableNodes, now)
                        }

                        is ScreenReading.ActiveRide -> {
                            diag("BRANCH ACTIVE_RIDE_SCREEN -> motorista em navegação/viagem")
                            handleTransitionToActiveRide(now)
                        }

                        is ScreenReading.Unknown -> Unit
                    }
                } else if (
                    (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (now - lastScreenshotTimestamp > OCR_MIN_INTERVAL_MS) &&
                    !isScreenUnsafeForCapture()
                ) {
                    // OCR fallback quando a árvore veio sem NENHUM texto (caso da 99, que desenha a oferta em canvas Flutter).
                    // Precisa cobrir TANTO abertura de janela quanto mudança de conteúdo: o card de oferta da 99
                    // aparece dentro da mesma HomePageActivity, sem trocar de janela.
                    diag(
                        "BRANCH OCR_FALLBACK disparado type=${AccessibilityEvent.eventTypeToString(eventType)} " +
                                "msDesdeUltimoOcr=${now - lastScreenshotTimestamp}"
                    )
                    performOcrFallback(packageName, clickableNodes)
                } else if (extractedTexts.isEmpty()) {
                    diag(
                        "BRANCH OCR_BLOQUEADO pkg=$packageName type=${AccessibilityEvent.eventTypeToString(eventType)} " +
                                "overlayRunning=${BubbleOverlayService.isRunning()} " +
                                "msDesdeUltimoOcr=${now - lastScreenshotTimestamp}"
                    )
                }
            } finally {
                isProcessingTree.set(false)
            }
        }
    }

    private fun handleManualClickEvent(event: AccessibilityEvent, now: Long) {
        val clickedTexts = mutableListOf<String>()
        event.text?.forEach { if (!it.isNullOrBlank()) clickedTexts.add(it.toString()) }
        event.contentDescription?.let { if (it.isNotBlank()) clickedTexts.add(it.toString()) }

        val src = try { event.source } catch (_: Exception) { null }
        if (src != null) {
            src.text?.toString()?.let { if (it.isNotBlank()) clickedTexts.add(it) }
            src.contentDescription?.toString()?.let { if (it.isNotBlank()) clickedTexts.add(it) }
            src.viewIdResourceName?.let { if (it.isNotBlank()) clickedTexts.add(it) }
        }

        val isAccept = clickedTexts.any { ParsingEngine.isAcceptActionText(it) }
        val isDecline = clickedTexts.any { ParsingEngine.isDeclineActionText(it) }

        if (isAccept) {
            commitPendingOfferAsAccepted(now)
        } else if (isDecline) {
            clearPendingOfferAndDismissOverlay(now)
        }
    }

    private fun handleTransitionToActiveRide(now: Long) {
        // Se havia uma oferta pendente recente (ex: motorista aceitou no gesto ou botão não reportou clique)
        // consolida a oferta pendente no banco e fecha o overlay para não atrapalhar a navegação
        val pending = pendingOffer
        if (pending != null && (now - pendingOfferTimestamp) <= PENDING_OFFER_VALIDITY_MS) {
            commitPendingOfferAsAccepted(now)
        } else if (BubbleOverlayService.isRunning() && (now - lastAcceptedTimestamp) > ACCEPTED_STREET_VIEW_GRACE_MS) {
            BubbleOverlayService.stop(this)
        }
        isRideCurrentlyActive = true
        activeRideStartTime = now
    }

    private fun commitPendingOfferAsAccepted(now: Long) {
        val offerToSave = pendingOffer ?: return
        if ((now - pendingOfferTimestamp) > PENDING_OFFER_VALIDITY_MS) {
            pendingOffer = null
            pendingOfferRideId = null
            return
        }

        // Consome a oferta pendente para nunca salvar duplicado
        pendingOffer = null
        val rideId = pendingOfferRideId
        pendingOfferRideId = null
        isRideCurrentlyActive = true
        activeRideStartTime = now
        lastAcceptedTimestamp = now
        declineCooldownUntil = now + 2500L

        // Ao aceitar a corrida, se houver endereço, mantém o overlay por mais 8 segundos com o Street View aberto pro motorista ver a fachada!
        BubbleOverlayService.onRideAcceptedShowStreetViewBriefly(this, offerToSave.destinationAddress)

        val finalRideId = if (rideId != null) {
            db.acceptRide(rideId)
            rideId
        } else {
            val existing = db.findRecentDuplicateRide(offerToSave.platform, offerToSave.price, offerToSave.distanceKm)
            existing?.id ?: db.insertRide(
                platform = offerToSave.platform,
                gross = offerToSave.price,
                distance = offerToSave.distanceKm,
                net = offerToSave.netProfit,
                status = RideRecord.STATUS_ESTIMADO,
                originalPrice = offerToSave.price
            )
        }

        diag("CORRIDA ACEITA E CONFIRMADA [#${finalRideId} - ${offerToSave.platform}]: R$ ${offerToSave.price} (${offerToSave.distanceKm} km)")

        sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
            setPackage(this@RotaAccessibilityService.packageName)
        })
    }

    private fun clearPendingOfferAndDismissOverlay(now: Long) {
        val rideId = pendingOfferRideId
        if (rideId != null) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    db.deleteRide(rideId)
                    sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                        setPackage(this@RotaAccessibilityService.packageName)
                    })
                } catch (_: Exception) {}
            }
        }
        pendingOffer = null
        pendingOfferRideId = null
        isRideCurrentlyActive = false
        declineCooldownUntil = now + 2500L
        BubbleOverlayService.stop(this)
        diag("Corrida recusada pelo motorista. Oferta descartada do banco e overlay fechado.")
    }

    private fun isOwnAppForeground(): Boolean {
        val activePkg = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        if (activePkg.contains("com.drifly.app", ignoreCase = true)) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val ownAppVisible = windows?.any { win ->
                    val isAppWindow = win.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            (win.isActive || win.isFocused)
                    val pkg = win.root?.packageName?.toString() ?: ""
                    isAppWindow && pkg.contains("com.drifly.app", ignoreCase = true)
                } == true
                if (ownAppVisible) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun isScreenUnsafeForCapture(): Boolean =
        BubbleOverlayService.isRunning() ||
            BubbleOverlayService.wasRecentlyDismissed() ||
            isOwnAppForeground()

    private fun isSupportedPackageName(pkg: String): Boolean {
        val lower = pkg.lowercase()
        if (lower.contains("com.drifly.app")) return false
        return lower.contains("ubercab") ||
                lower.contains("uber") ||
                lower.contains("app99") ||
                lower.contains("taxis99") ||
                lower.contains("didi") ||
                lower.contains("ifood") ||
                lower.contains("driver")
    }

    private fun processDetectedOffer(
        offer: RideOffer,
        clickableNodes: List<AccessibilityNodeInfo>,
        timestamp: Long
    ) {
        if (timestamp < declineCooldownUntil) {
            diag("PROCESS_OFFER ignorada: em cooldown de recusa (faltam ${declineCooldownUntil - timestamp}ms)")
            return
        }

        // Se uma nova oferta legítima tocou na tela, significa que não estamos em viagem bloqueada
        isRideCurrentlyActive = false

        val currentHash = offer.hashCode()
        // Dedup da mesma corrida enquanto ela está tocando na tela (evita reprocessar o mesmo card a cada segundo)
        val existingPending = pendingOffer
        val isSameOfferAsPending = existingPending != null &&
                existingPending.platform == offer.platform &&
                Math.abs(existingPending.price - offer.price) < 0.01 &&
                Math.abs(existingPending.distanceKm - offer.distanceKm) < 0.1

        if (isSameOfferAsPending && (timestamp - lastOfferTimestamp) < SAME_OFFER_SUPPRESS_MS) {
            return
        }

        if (currentHash != lastOfferHash || (timestamp - lastOfferTimestamp) > 4500L) {
            lastOfferHash = currentHash
            lastOfferTimestamp = timestamp

            val finalOffer = if (prefs.isModoCasaActive) {
                val eval = ModoCasaHelper.evaluateRideTowardsHome(this, offer.destinationAddress)
                offer.copy(
                    isHeadingHome = eval.isHeadingHome,
                    homeDistanceDeltaKm = eval.deltaKm
                )
            } else {
                offer
            }

            diag("OFERTA DETECTADA [${finalOffer.platform}]: R$ ${finalOffer.price} | ${finalOffer.distanceKm}km | R$/km ${finalOffer.ratePerKm} | Lucro R$ ${finalOffer.netProfit} | ModoCasa: ${finalOffer.isHeadingHome} (${finalOffer.homeDistanceDeltaKm}km)")

            // 1. Registra a oferta como OFERTA (oculta do faturamento). Só vira ESTIMADO quando o motorista ACEITAR.
            val existingRide = db.findRecentDuplicateRide(finalOffer.platform, finalOffer.price, finalOffer.distanceKm)
            val rideId = existingRide?.id ?: db.insertRide(
                platform = finalOffer.platform,
                gross = finalOffer.price,
                distance = finalOffer.distanceKm,
                net = finalOffer.netProfit,
                status = RideRecord.STATUS_OFERTA,
                originalPrice = finalOffer.price
            )

            // Guarda como oferta pendente associada ao ID
            setPendingOffer(finalOffer, timestamp, rideId)

            // Atualiza Dashboard / Card Última Corrida
            sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })

            // 2. Dispara Bolha/Overlay e Notificação diretamente na tela
            BubbleOverlayService.showOffer(this, finalOffer, rideId)

            // 3. Processa Lógica de Aceite / Recusa Automática
            if (clickableNodes.isNotEmpty()) {
                handleAutomation(finalOffer, clickableNodes, timestamp, rideId)
            }
        }
    }

    private fun handleReceipt(receipt: ReceiptResult) {
        isRideCurrentlyActive = false
        pendingOffer = null
        pendingOfferRideId = null
        BubbleOverlayService.stop(this)

        val ride = db.getLatestEstimatedRide(receipt.platform) ?: return
        if (ride.id == lastHandledRideId) return
        lastHandledRideId = ride.id
        lastReceiptProcessedTimestamp = System.currentTimeMillis()

        diag("RECIBO DETECTADO [${receipt.platform}]: finalPrice=${receipt.finalPrice}, cancelado=${receipt.isCancelled}")

        if (receipt.isCompleted) {
            val finalPrice = receipt.finalPrice ?: ride.originalPrice
            val cost = ride.distanceKm * prefs.costPerKm
            val newNet = Math.max(0.0, Math.round((finalPrice - cost) * 100.0) / 100.0)
            db.updateRidePrice(ride.id, finalPrice, newNet, RideRecord.STATUS_CONCLUIDO)
        } else if (receipt.isCancelled) {
            db.updateRideStatus(ride.id, receipt.status, receipt.cancellationFee ?: 0.0)
        }

        sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
            setPackage(this@RotaAccessibilityService.packageName)
        })
    }

    private fun handleAutomation(
        offer: RideOffer,
        clickableNodes: List<AccessibilityNodeInfo>,
        timestamp: Long,
        rideId: Long
    ) {
        val matchesConditions = (offer.ratePerKm >= prefs.minRatePerKm) &&
                (offer.distanceKm <= prefs.maxDistanceKm)

        // REGRA DE SEGURANÇA: Se a nota do passageiro for baixa, NUNCA aceita automático
        if (offer.isLowRating) {
            return
        }

        if (prefs.autoAcceptEnabled && matchesConditions) {
            val accepted = if (prefs.isMotorcycleMode) {
                performSwipeAcceptGesture()
                true
            } else {
                performClickAccept(clickableNodes)
            }
            if (accepted) {
                commitPendingOfferAsAccepted(timestamp)
            }
        } else if (prefs.autoDeclineEnabled && !matchesConditions) {
            // Recusa automática se não bateu os critérios
            val declined = performClickDecline(clickableNodes)
            if (declined) {
                clearPendingOfferAndDismissOverlay(timestamp)
            }
        }
    }

    private fun performClickAccept(nodes: List<AccessibilityNodeInfo>): Boolean {
        for (node in nodes) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            val id = (node.viewIdResourceName ?: "")
            if (ParsingEngine.isAcceptActionText(text) || ParsingEngine.isAcceptActionText(id)) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun performClickDecline(nodes: List<AccessibilityNodeInfo>): Boolean {
        for (node in nodes) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            val id = (node.viewIdResourceName ?: "")
            if (ParsingEngine.isDeclineActionText(text) || ParsingEngine.isDeclineActionText(id)) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun performSwipeAcceptGesture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val startX = displayMetrics.widthPixels * 0.2f
            val endX = displayMetrics.widthPixels * 0.85f
            val y = displayMetrics.heightPixels * 0.78f // Região inferior onde fica o slider

            val swipePath = Path().apply {
                moveTo(startX, y)
                lineTo(endX, y)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 250))
                .build()

            dispatchGesture(gesture, null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun performOcrFallback(packageName: String, clickableNodes: List<AccessibilityNodeInfo>) {
        if (isScreenUnsafeForCapture()) return
        lastScreenshotTimestamp = System.currentTimeMillis()
        diag("OCR_START pkg=$packageName")
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        diag("OCR_SCREENSHOT_OK")
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                val buffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                val bitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                buffer.close()

                                if (bitmap != null) {
                                    val image = InputImage.fromBitmap(bitmap, 0)
                                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                    recognizer.process(image)
                                        .addOnSuccessListener { visionText ->
                                            val ocrTexts = ArrayList<String>()
                                            for (block in visionText.textBlocks) {
                                                for (line in block.lines) {
                                                    val text = line.text.trim()
                                                    if (text.isNotBlank()) {
                                                        ocrTexts.add(text)
                                                    }
                                                }
                                            }
                                            if (ocrTexts.isNotEmpty() && !isScreenUnsafeForCapture()) {
                                                diag("OCR_TEXTS n=${ocrTexts.size} :: ${ocrTexts.joinToString(" | ").take(400)}")
                                                val ocrNow = System.currentTimeMillis()
                                                if (isRideCurrentlyActive && ParsingEngine.isIdleDriverScreen(ocrTexts)) {
                                                    diag("BRANCH OCR_IDLE_SCREEN -> motorista na tela inicial, limpa status de corrida ativa")
                                                    isRideCurrentlyActive = false
                                                }
                                                when (val reading = ScreenRouter.read(
                                                    packageName = packageName,
                                                    texts = ocrTexts,
                                                    costPerKm = prefs.costPerKm,
                                                    minRatingThreshold = prefs.minPassengerRating,
                                                    isRideCurrentlyActive = isRideCurrentlyActive
                                                )) {
                                                    is ScreenReading.Receipt -> {
                                                        consecutiveEmptyOcrCount = 0
                                                        ocrFastRetryPending = false
                                                        val receipt = reading.receipt
                                                        diag("BRANCH OCR_RECEIPT ${receipt.platform} completed=${receipt.isCompleted} cancelled=${receipt.isCancelled}")
                                                        val currentReceiptHash = receipt.hashCode()
                                                        if (currentReceiptHash != lastReceiptHash && (ocrNow - lastReceiptProcessedTimestamp) > 8_000L) {
                                                            lastReceiptHash = currentReceiptHash
                                                            lastReceiptTimestamp = ocrNow
                                                            handleReceipt(receipt)
                                                        }
                                                    }
                                                    is ScreenReading.Offer -> {
                                                        consecutiveEmptyOcrCount = 0
                                                        ocrFastRetryPending = false
                                                        processDetectedOffer(reading.offer, clickableNodes, ocrNow)
                                                    }
                                                    is ScreenReading.ActiveRide -> {
                                                        consecutiveEmptyOcrCount = 0
                                                        ocrFastRetryPending = false
                                                        diag("BRANCH OCR_ACTIVE_RIDE_SCREEN -> motorista em navegação/viagem")
                                                        handleTransitionToActiveRide(ocrNow)
                                                    }
                                                    is ScreenReading.Unknown -> {
                                                        diag("OCR_PARSE_NULL (texto lido, mas sem preço+distância válidos)")
                                                        markOcrResult(ocrTexts.size)
                                                    }
                                                }
                                            } else {
                                                diag("OCR_TEXTS vazio/ignorado (n=${ocrTexts.size}, overlay=${BubbleOverlayService.isRunning()})")
                                                markOcrResult(ocrTexts.size)
                                            }
                                        }
                                        .addOnCompleteListener {
                                            bitmap.recycle()
                                        }
                                        .addOnFailureListener { err ->
                                            diag("OCR_MLKIT_FAIL ${err.javaClass.simpleName}: ${err.message}")
                                        }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Erro ao processar OCR fallback", e)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        diag("OCR_SCREENSHOT_FAIL code=$errorCode")
                        Log.d(TAG, "takeScreenshot falhou com código: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao invocar takeScreenshot para OCR", e)
        }
    }

    /**
     * Classifica o resultado de uma captura de OCR.
     *
     * Captura "quase vazia" (só a barra de status) normalmente significa que a tela estava no meio
     * de uma transição — frame preto. Nesse caso pedimos uma nova tentativa rápida, em vez de
     * esperar ~5s do vigia ou (pior) o próximo evento, que a 99 pode nunca emitir.
     */
    private fun markOcrResult(textCount: Int) {
        if (textCount <= OCR_EMPTY_TEXT_THRESHOLD) {
            consecutiveEmptyOcrCount++
            ocrFastRetryPending = true
            diag("OCR_INCOMPLETO n=$textCount -> nova captura em ${OCR_FAST_RETRY_MS}ms (#$consecutiveEmptyOcrCount)")
            // Reagenda na hora: esperar o próximo ciclo de 5s poderia perder a janela da oferta.
            if (consecutiveEmptyOcrCount <= OCR_MAX_FAST_RETRIES) {
                ocrHandler.removeCallbacks(ocrWatchdogRunnable)
                ocrHandler.postDelayed(ocrWatchdogRunnable, OCR_FAST_RETRY_MS)
            }
        } else {
            consecutiveEmptyOcrCount = 0
            ocrFastRetryPending = false
        }
    }

    private fun diag(message: String) {
        if (!DIAG_ENABLED) return
        AppLogger.i(TAG_DIAG, message)
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        texts: ArrayList<String>,
        clickables: ArrayList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 30 || texts.size > 120) return

        val nodePkg = node.packageName?.toString() ?: ""
        if (nodePkg.contains("com.drifly.app", ignoreCase = true)) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim() else null

        if (!text.isNullOrBlank()) texts.add(text)
        if (!desc.isNullOrBlank() && desc != text) texts.add(desc)
        if (!hint.isNullOrBlank() && hint != text && hint != desc) texts.add(hint)

        if (node.isClickable) {
            clickables.add(node)
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                traverseNodes(child, texts, clickables, depth + 1)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        ocrHandler.removeCallbacks(ocrWatchdogRunnable)
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "RotaAccessibilityService"
        private const val TAG_DIAG = "DriFlyDiag"
        private const val DIAG_ENABLED = true
        const val ACTION_RIDE_UPDATED = "com.drifly.app.ACTION_RIDE_UPDATED"
        private const val PENDING_OFFER_VALIDITY_MS = 45_000L
        private const val ACCEPTED_STREET_VIEW_GRACE_MS = 8_500L

        // Janela em que a MESMA oferta (mesmo preço e distância) não é exibida de novo pelo overlay.
        // Precisa cobrir a duração do card na tela (~15-20s), senão o overlay reaparece durante a oferta.
        private const val SAME_OFFER_SUPPRESS_MS = 20_000L

        // Intervalo mínimo entre capturas de tela + OCR (evita custo excessivo de CPU/bateria).
        // O OCR "quente" leva ~400ms no dispositivo de referência, então 1200ms mantém o custo baixo.
        private const val OCR_MIN_INTERVAL_MS = 1_200L

        // Vigia: tenta o OCR a cada 5s enquanto o app monitorado está na tela sem texto na árvore.
        private const val OCR_WATCHDOG_INTERVAL_MS = 5_000L

        // Quando a captura vem incompleta (tela em transição), tenta de novo rapidamente.
        // Precisa ser MAIOR que OCR_MIN_INTERVAL_MS, senão a nova tentativa é descartada pelo
        // intervalo mínimo e a oferta só é lida no ciclo seguinte de 5s (foi o atraso observado).
        private const val OCR_FAST_RETRY_MS = 1_300L
        private const val OCR_MAX_FAST_RETRIES = 10
        private const val OCR_EMPTY_TEXT_THRESHOLD = 3

        @Volatile
        var pendingOffer: RideOffer? = null
            private set

        @Volatile
        var pendingOfferTimestamp: Long = 0L
            private set

        @Volatile
        var pendingOfferRideId: Long? = null
            private set

        @Volatile
        var isRideCurrentlyActive: Boolean = false

        fun setPendingOffer(offer: RideOffer, timestamp: Long = System.currentTimeMillis(), rideId: Long? = null) {
            pendingOffer = offer
            pendingOfferTimestamp = timestamp
            pendingOfferRideId = rideId
        }
    }
}
