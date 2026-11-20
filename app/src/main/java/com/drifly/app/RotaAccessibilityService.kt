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
    private var lastActiveNavTreeDiagTimestamp: Long = 0L
    private var activeRideStartTime: Long = 0L
    private var lastAcceptedTimestamp: Long = 0L

    @Volatile
    private var lastDemandNavigationTimestamp: Long = 0L

    @Volatile
    private var lastFreeGpsSeenTimestamp: Long = 0L

    @Volatile
    private var offerArrivedDuringFreeGps: Boolean = false

    @Volatile
    private var previousConsecutiveRideId: Long = -1L

    @Volatile
    private var lastConsecutiveAcceptTimestamp: Long = 0L

    @Volatile
    private var lastAutoCompletedIdleRideId: Long = -1L

    @Volatile
    private var lastAutoCompletedIdleTimestamp: Long = 0L

    @Volatile
    private var isAcceptGestureInFlight: Boolean = false

    // Pacote do app de corrida visto por último (usado para reler a tela ao verificar um aceite).
    @Volatile
    private var lastSupportedPackage: String = ""

    // A oferta pendente chegou enquanto outra corrida já estava em andamento. Nesse caso a tela de
    // navegação que continua visível NÃO prova que o motorista aceitou a nova oferta.
    @Volatile
    private var offerArrivedDuringRide: Boolean = false

    @Volatile
    private var activeRideSeenDropoffPhase: Boolean = false

    // Momento em que o diálogo "Deseja mesmo cancelar esta corrida?" foi visto. Se a tela voltar
    // ao ocioso logo depois, a corrida é CANCELADA sem taxa (e não auto-concluída).
    @Volatile
    private var driverCancellationPromptAt: Long = 0L

    @Volatile
    private var idleSeenSince: Long = 0L

    // Vigia de OCR: a 99 desenha o card em canvas e emite pouquíssimos eventos de acessibilidade,
    // então não dá para depender de evento para capturar a oferta. Este watchdog tenta o OCR
    // periodicamente enquanto o app monitorado está na tela e a árvore veio sem nenhum texto
    // (ou com poucos textos nativos de GPS sem a camada Flutter).
    private val ocrHandler = Handler(Looper.getMainLooper())
    private var consecutiveEmptyOcrCount = 0

    @Volatile
    private var ocrFastRetryPending = false

    private lateinit var prefs: PreferencesManager
    private lateinit var db: DatabaseHelper

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLogger.init(this)
        AppLogger.installCrashHandler(this)
        prefs = PreferencesManager(this)
        db = DatabaseHelper(this)
        try {
            Log.i(TAG, "RotaAccessibilityService inicializado com sucesso.")
        } catch (ignored: Throwable) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.init(this)
        ocrHandler.removeCallbacks(ocrWatchdogRunnable)
        ocrHandler.postDelayed(ocrWatchdogRunnable, OCR_WATCHDOG_INTERVAL_MS)
        diag("WATCHDOG iniciado")
    }

    private fun hasRecentDemandNavigation(now: Long): Boolean =
        lastDemandNavigationTimestamp != 0L && (now - lastDemandNavigationTimestamp) in 0..DEMAND_NAV_GRACE_MS

    /**
     * Mantém a janela de elegibilidade para recibos de conclusão ativa tanto durante a corrida
     * quanto nos segundos seguintes a um modal intermediário pós-corrida (ex: "Pagamento recebido!"
     * ou "Como foi sua corrida" seguido do toast "Valor da corrida | +R$17,80").
     */
    private fun isEffectiveRideActiveForReceipt(now: Long): Boolean =
        isRideCurrentlyActive ||
            (lastHandledRideId != -1L && (now - lastReceiptProcessedTimestamp) in 0..90_000L) ||
            (lastAutoCompletedIdleRideId != -1L && (now - lastAutoCompletedIdleTimestamp) in 0..30_000L)

    /**
     * Vigia periódico de OCR.
     *
     * Por que existe: a 99 renderiza o card de oferta em canvas Flutter, sem nenhum texto na árvore
     * de acessibilidade (ou apenas uma barra nativa de GPS), e dispara pouquíssimos eventos.
     */
    private val ocrWatchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val hasFreshPending = pendingOffer != null &&
                !offerArrivedDuringRide &&
                AcceptanceRules.isPendingStillValid(pendingOfferTimestamp, now)
            var nextDelay = if (hasFreshPending) OCR_HYBRID_INTERVAL_MS else OCR_WATCHDOG_INTERVAL_MS
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
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val activePkg = try { root.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        if (activePkg.contains("com.drifly.app", ignoreCase = true)) return false
        if (!isSupportedPackageName(activePkg)) return false
        lastSupportedPackage = activePkg

        val texts = ArrayList<String>()
        val clickables = ArrayList<AccessibilityNodeInfo>()
        try { traverseNodes(root, texts, clickables, 0) } catch (_: Exception) {}

        val hasFreshPending = pendingOffer != null &&
            !offerArrivedDuringRide &&
            AcceptanceRules.isPendingStillValid(pendingOfferTimestamp, now)

        if (texts.isNotEmpty()) {
            if (ParsingEngine.isDemandHotspotScreen(texts)) {
                lastDemandNavigationTimestamp = now
            } else if (ParsingEngine.isIdleDriverScreen(texts) && !isRideCurrentlyActive) {
                lastDemandNavigationTimestamp = 0L
            }

            val reading = ScreenRouter.read(
                packageName = activePkg,
                texts = texts,
                costPerKm = prefs.costPerKm,
                minRatingThreshold = prefs.minPassengerRating,
                isRideCurrentlyActive = isEffectiveRideActiveForReceipt(now)
            )
            if (reading !is ScreenReading.Unknown ||
                !ParsingEngine.shouldVerifyFlutterLayerViaOcr(activePkg, texts, hasFreshPending)
            ) {
                ocrFastRetryPending = false
                return false
            }

            val requiredInterval = if (hasFreshPending) OCR_HYBRID_INTERVAL_MS else OCR_WATCHDOG_INTERVAL_MS
            if (now - lastScreenshotTimestamp < requiredInterval) return false

            diag("WATCHDOG HYBRID_OCR pkg=$activePkg texts=${texts.size} hasPending=$hasFreshPending")
            performOcrFallback(activePkg, clickables)
            return true
        }

        if (now - lastScreenshotTimestamp < OCR_MIN_INTERVAL_MS) return false
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
        lastSupportedPackage = packageName

        // DIAGNÓSTICO: registra eventos recebidos dos apps monitorados (reduz ruído durante navegação ativa)
        val diagEvtInterval = if (isRideCurrentlyActive && pendingOffer == null) 10_000L else 1_000L
        if (now - lastDiagEventTimestamp > diagEvtInterval) {
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
            if (notifTexts.isNotEmpty() && ParsingEngine.isLikelyRideOfferNotification(notifTexts)) {
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

                val shouldLogTreeNow = !isRideCurrentlyActive ||
                    pendingOffer != null ||
                    (now - lastActiveNavTreeDiagTimestamp > 15_000L)
                if (shouldLogTreeNow) {
                    lastActiveNavTreeDiagTimestamp = now
                    diag("TREE pkg=$packageName roots=${topRoots.size} texts=${extractedTexts.size} click=${clickableNodes.size} rideActive=$isRideCurrentlyActive")
                    if (extractedTexts.isNotEmpty()) {
                        diag("TEXTS pkg=$packageName :: ${extractedTexts.joinToString(" | ").take(400)}")
                    }
                }

                if (extractedTexts.isNotEmpty()) {
                    if (ParsingEngine.isDemandHotspotScreen(extractedTexts)) {
                        lastDemandNavigationTimestamp = now
                    } else if (ParsingEngine.isIdleDriverScreen(extractedTexts) && !isRideCurrentlyActive) {
                        lastDemandNavigationTimestamp = 0L
                    }

                    // Se a corrida ativa ficou sem eventos de navegação por mais de 45 min, expira o bloqueio
                    if (isRideCurrentlyActive && (now - activeRideStartTime > 45 * 60 * 1000L)) {
                        diag("ACTIVE_RIDE expirada por timeout (45 min)")
                        isRideCurrentlyActive = false
                    }

                    // Classificação em ordem segura:
                    // recibo/cancelamento -> nova oferta -> tela de corrida ativa.
                    // (O recibo precisa vir primeiro: ver comentário em ScreenRouter.)
                    // IMPORTANTE: não limpar isRideCurrentlyActive antes de ScreenRouter.read, pois o resumo
                    // pós-corrida da 99 ("Valor da corrida | +R$17,80 | ... | Buscando") traz "Buscando" na mesma tela!
                    when (val reading = ScreenRouter.read(
                        packageName = packageName,
                        texts = extractedTexts,
                        costPerKm = prefs.costPerKm,
                        minRatingThreshold = prefs.minPassengerRating,
                        isRideCurrentlyActive = isEffectiveRideActiveForReceipt(now)
                    )) {
                        is ScreenReading.Receipt -> {
                            val receipt = reading.receipt
                            diag("BRANCH RECEIPT ${receipt.platform} completed=${receipt.isCompleted} cancelled=${receipt.isCancelled} price=${receipt.finalPrice}")
                            val currentReceiptHash = receipt.hashCode()
                            if (currentReceiptHash != lastReceiptHash || (now - lastReceiptProcessedTimestamp) > 8_000L) {
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
                            if (shouldLogTreeNow) {
                                diag("BRANCH ACTIVE_RIDE_SCREEN -> motorista em navegação/viagem")
                            }
                            handleTransitionToActiveRide(now, extractedTexts, packageName)
                        }

                        is ScreenReading.Unknown -> onUnknownReading(
                            packageName = packageName,
                            texts = extractedTexts,
                            clickableNodes = clickableNodes,
                            now = now,
                            allowHybridOcr = true
                        )
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
        val isCancelConfirm = clickedTexts.any { it.trim().equals("cancelar", ignoreCase = true) }

        if (isCancelConfirm && driverCancellationPromptAt != 0L && (now - driverCancellationPromptAt) in 0..60_000L) {
            cancelActiveRideFromPrompt(now)
            return
        }

        if (isAccept) {
            commitPendingOfferAsAccepted(now, RideRecord.EVIDENCE_CLICK)
        } else if (isDecline) {
            clearPendingOfferAndDismissOverlay(now)
        }
    }

    private fun handleTransitionToActiveRide(
        now: Long,
        texts: List<String> = emptyList(),
        packageName: String = lastSupportedPackage,
        evidence: String = RideRecord.EVIDENCE_ACTIVE_RIDE
    ) {
        if (ParsingEngine.isInTripOrDropoffPhaseScreen(texts)) {
            activeRideSeenDropoffPhase = true
        }
        val currentPending = pendingOffer
        val resolvedPlatform = ParsingEngine.resolvePlatform(packageName, texts)
        val samePlatformAsPending = currentPending != null &&
            (resolvedPlatform == "CORRIDA" || resolvedPlatform.equals(currentPending.platform, ignoreCase = true))

        // A tela de corrida ativa só prova o aceite quando a oferta pendente é recente E não chegou
        // durante outra corrida (senão a navegação anterior continua na tela mesmo sem aceitar).
        if (AcceptanceRules.canCommitOnActiveRideScreen(
                hasPending = currentPending != null,
                pendingTimestamp = pendingOfferTimestamp,
                now = now,
                offerArrivedDuringRide = offerArrivedDuringRide
            )
        ) {
            commitPendingOfferAsAccepted(now, evidence)
        } else if (
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = currentPending != null,
                pendingTimestamp = pendingOfferTimestamp,
                now = now,
                offerArrivedDuringRide = offerArrivedDuringRide,
                activeRideSeenDropoffPhase = activeRideSeenDropoffPhase,
                lastAcceptedTimestamp = lastAcceptedTimestamp,
                samePlatform = samePlatformAsPending,
                isPickupPhase = ParsingEngine.isPickupPhaseScreen(texts)
            )
        ) {
            // O motorista aceitou uma corrida consecutiva diretamente no canvas da 99 durante a viagem
            // anterior e a tela entrou na FASE DE EMBARQUE da nova corrida. Isso sim é prova real.
            diag("BRANCH CONSECUTIVE_PICKUP_TRANSITION -> corrida anterior concluída e corrida consecutiva iniciada")
            commitPendingOfferAsAccepted(now, RideRecord.EVIDENCE_PICKUP_TRANSITION)
        } else if (BubbleOverlayService.isRunning() && (now - lastAcceptedTimestamp) > ACCEPTED_STREET_VIEW_GRACE_MS) {
            BubbleOverlayService.stop(this)
        }
        isRideCurrentlyActive = true
        activeRideStartTime = now
    }

    /**
     * Trata leituras desconhecidas:
     * 1. Se o motorista voltou para tela ociosa por >= 2s, descarta oferta pendente (e conclui corrida ativa encerrada).
     * 2. Se o HUD nativo de GPS (km/h / Chegada prevista) confirma transição de oferta recente fora de corrida/demanda,
     *    promove a oferta para corrida ativa.
     * 3. Em apps híbridos (99), dispara OCR throttled para ler a camada Flutter subjacente.
     */
    private fun onUnknownReading(
        packageName: String,
        texts: List<String>,
        clickableNodes: List<AccessibilityNodeInfo>,
        now: Long,
        allowHybridOcr: Boolean
    ) {
        if (ParsingEngine.isDriverCancellationPromptScreen(texts)) {
            driverCancellationPromptAt = now
            diag("CANCEL_PROMPT: motorista abriu confirmação de cancelamento; aguardando desfecho")
            return
        }
        val looksIdle = ParsingEngine.isIdleDriverScreen(texts) &&
                texts.none { ParsingEngine.isAcceptActionText(it) }
        if (looksIdle) {
            // Exige a tela ociosa por >= 2s seguidos: uma leitura parcial do card não pode descartar a oferta.
            if (idleSeenSince == 0L) {
                idleSeenSince = now
            } else if (now - idleSeenSince >= 2_000L) {
                discardPendingOfferOnIdleScreen(now, packageName)
            }
            return
        }

        idleSeenSince = 0L

        val joinedLower = texts.joinToString(" ").lowercase()
        val hasGpsHudOnScreen = joinedLower.contains("km/h") || joinedLower.contains("chegada prevista")

        // Se a corrida está ativa e o HUD de GPS da 99 continua na tela ("40 km/h | Chegada prevista"),
        // renova o timestamp de atividade da corrida para manter isRideCurrentlyActive vivo até o desembarque.
        if (isRideCurrentlyActive && hasGpsHudOnScreen) {
            activeRideStartTime = now
        } else if (!isRideCurrentlyActive && pendingOffer == null && hasGpsHudOnScreen) {
            lastFreeGpsSeenTimestamp = now
        }

        val currentPending = pendingOffer
        val isNormalOfferGpsTransition = currentPending != null &&
            !offerArrivedDuringRide &&
            AcceptanceRules.isPendingStillValid(pendingOfferTimestamp, now) &&
            (AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = texts,
                pendingOffer = currentPending,
                hasRecentDemandNavigation = hasRecentDemandNavigation(now)
            ) || AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = texts,
                pendingOffer = currentPending,
                offerArrivedDuringRide = offerArrivedDuringRide,
                offerArrivedDuringFreeGps = offerArrivedDuringFreeGps,
                hasRecentDemandNavigation = hasRecentDemandNavigation(now)
            ))

        // REGRA 1.2.0: NUNCA promover uma oferta que chegou DURANTE outra corrida só porque o HUD
        // da corrida atual contém uma rua da oferta. Era a causa dos falsos aceites #193/#210/#212/#215.
        // Consecutiva só é confirmada pela transição real de embarque (CONSECUTIVE_PICKUP_TRANSITION).
        if (isNormalOfferGpsTransition) {
            diag("BRANCH GPS_HUD_MATCH_OFFER -> transição confirmada para corrida ativa (consecutive=false, evidence=HUD_FRESH)")
            handleTransitionToActiveRide(
                now = now,
                texts = texts,
                packageName = packageName,
                evidence = RideRecord.EVIDENCE_HUD_FRESH
            )
            return
        }

        if (allowHybridOcr &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !isScreenUnsafeForCapture() &&
            now >= declineCooldownUntil
        ) {
            val hasFreshPending = currentPending != null &&
                ((!offerArrivedDuringRide && AcceptanceRules.isPendingStillValid(pendingOfferTimestamp, now)) ||
                    (offerArrivedDuringRide && activeRideSeenDropoffPhase && (now - pendingOfferTimestamp) in 0..120_000L))
            if (ParsingEngine.shouldVerifyFlutterLayerViaOcr(packageName, texts, hasFreshPending)) {
                val requiredInterval = if (hasFreshPending) OCR_HYBRID_INTERVAL_MS else OCR_WATCHDOG_INTERVAL_MS
                if ((now - lastScreenshotTimestamp) >= requiredInterval) {
                    diag("BRANCH HYBRID_OCR_FALLBACK pkg=$packageName texts=${texts.size} hasPending=$hasFreshPending")
                    performOcrFallback(packageName, clickableNodes)
                }
            }
        }
    }

    private fun cancelActiveRideFromPrompt(now: Long) {
        val platform = ParsingEngine.resolvePlatform(lastSupportedPackage)
        val targetPlatform = if (platform != "CORRIDA") platform else null
        val activeRide = db.getLatestEstimatedRide(targetPlatform, maxAgeMs = 4 * 60 * 60 * 1000L)
        if (activeRide != null) {
            db.updateRideStatus(
                activeRide.id,
                RideRecord.STATUS_CANCELADO,
                0.0,
                RideRecord.EVIDENCE_CANCEL_PROMPT
            )
            lastHandledRideId = activeRide.id
            lastReceiptProcessedTimestamp = now
            diag("CANCEL_PROMPT CONFIRMADO [#${activeRide.id} - ${activeRide.platform}]: cancelada sem taxa")
            sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })
        }
        driverCancellationPromptAt = 0L
        isRideCurrentlyActive = false
        pendingOffer = null
        pendingOfferRideId = null
        offerArrivedDuringRide = false
        offerArrivedDuringFreeGps = false
        activeRideSeenDropoffPhase = false
        BubbleOverlayService.stop(this)
    }

    private fun discardPendingOfferOnIdleScreen(
        now: Long = System.currentTimeMillis(),
        packageName: String = lastSupportedPackage
    ) {
        if (pendingOffer != null) {
            diag("Tela ociosa sem card: oferta pendente descartada (não aceita)")
        }
        // Se o motorista estava em uma corrida ativa (há pelo menos 90s) e retornou de forma estável
        // (>= 2s) para a tela ociosa ("Buscando" / "Você está online"), garante que a corrida encerrada
        // seja automaticamente marcada como CONCLUIDO mesmo que o motorista tenha fechado o popup de
        // avaliação entre duas capturas de OCR.
        if (isRideCurrentlyActive) {
            val platform = ParsingEngine.resolvePlatform(packageName)
            val targetPlatform = if (platform != "CORRIDA") platform else null
            val activeRide = db.getLatestEstimatedRide(targetPlatform, maxAgeMs = 4 * 60 * 60 * 1000L)
            val promptRecent = driverCancellationPromptAt != 0L && (now - driverCancellationPromptAt) in 0..60_000L
            if (activeRide != null && promptRecent) {
                // O motorista confirmou "Cancelar" no diálogo e a tela voltou ao ocioso: NÃO é conclusão.
                db.updateRideStatus(
                    activeRide.id,
                    RideRecord.STATUS_CANCELADO,
                    0.0,
                    RideRecord.EVIDENCE_CANCEL_PROMPT
                )
                lastHandledRideId = activeRide.id
                lastReceiptProcessedTimestamp = now
                driverCancellationPromptAt = 0L
                diag("AUTO-CANCEL ON IDLE [#${activeRide.id} - ${activeRide.platform}]: cancelada pelo motorista, sem taxa")
                sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                    setPackage(this@RotaAccessibilityService.packageName)
                })
            } else if (activeRide != null && (now - activeRide.timestamp) >= MIN_ACTIVE_RIDE_DURATION_FOR_IDLE_COMPLETION_MS) {
                db.updateRideStatus(activeRide.id, RideRecord.STATUS_CONCLUIDO)
                lastAutoCompletedIdleRideId = activeRide.id
                lastAutoCompletedIdleTimestamp = now
                lastHandledRideId = activeRide.id
                lastReceiptProcessedTimestamp = now
                diag("AUTO-COMPLETE ON IDLE [#${activeRide.id} - ${activeRide.platform}]: R$ ${activeRide.grossPrice} marcada como CONCLUIDO ao retornar para tela ociosa")
                sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                    setPackage(this@RotaAccessibilityService.packageName)
                })
            } else {
                diag("BRANCH IDLE_SCREEN -> motorista online aguardando chamadas, limpa status de corrida ativa")
            }
        }
        pendingOffer = null
        pendingOfferRideId = null
        offerArrivedDuringRide = false
        offerArrivedDuringFreeGps = false
        activeRideSeenDropoffPhase = false
        previousConsecutiveRideId = -1L
        isRideCurrentlyActive = false
        idleSeenSince = 0L
        lastDemandNavigationTimestamp = 0L
        driverCancellationPromptAt = 0L
    }

    private fun commitPendingOfferAsAccepted(now: Long, evidence: String = RideRecord.EVIDENCE_CLICK) {
        val offerToSave = pendingOffer ?: return
        val validEvidence = when (evidence) {
            RideRecord.EVIDENCE_PICKUP_TRANSITION ->
                offerArrivedDuringRide &&
                    AcceptanceRules.canCommitOnConsecutivePickupTransition(
                        hasPending = true,
                        pendingTimestamp = pendingOfferTimestamp,
                        now = now,
                        offerArrivedDuringRide = offerArrivedDuringRide,
                        activeRideSeenDropoffPhase = activeRideSeenDropoffPhase,
                        lastAcceptedTimestamp = lastAcceptedTimestamp,
                        samePlatform = true,
                        isPickupPhase = true
                    )
            RideRecord.EVIDENCE_ACTIVE_RIDE, RideRecord.EVIDENCE_HUD_FRESH ->
                AcceptanceRules.canCommitOnActiveRideScreen(true, pendingOfferTimestamp, now, offerArrivedDuringRide)
            RideRecord.EVIDENCE_CLICK,
            RideRecord.EVIDENCE_GESTURE,
            RideRecord.EVIDENCE_OVERLAY_TAP ->
                AcceptanceRules.canCommitOnAcceptClick(true, pendingOfferTimestamp, now)
            else -> false
        }
        if (!validEvidence) {
            diag("ACEITE DESCARTADO: evidência inválida ($evidence) ou oferta expirada")
            pendingOffer = null
            pendingOfferRideId = null
            offerArrivedDuringRide = false
            offerArrivedDuringFreeGps = false
            activeRideSeenDropoffPhase = false
            return
        }

        val wasConsecutiveOffer = offerArrivedDuringRide

        // Consome a oferta pendente para nunca salvar duplicado
        pendingOffer = null
        val rideId = pendingOfferRideId
        pendingOfferRideId = null
        offerArrivedDuringRide = false
        offerArrivedDuringFreeGps = false
        activeRideSeenDropoffPhase = false
        driverCancellationPromptAt = 0L
        isRideCurrentlyActive = true
        activeRideStartTime = now
        lastAcceptedTimestamp = now
        declineCooldownUntil = now + 2500L

        // Se havia uma corrida anterior ainda como ESTIMADO na mesma plataforma (seja por corrida consecutiva
        // ou porque o recibo anterior foi fechado rapidamente antes de iniciar esta nova corrida), conclui a anterior.
        val prevEstimated = db.getLatestEstimatedRide(offerToSave.platform, maxAgeMs = 4 * 60 * 60 * 1000L)
        if (prevEstimated != null && prevEstimated.id != rideId && (wasConsecutiveOffer || (now - prevEstimated.timestamp) >= 60_000L)) {
            db.updateRideStatus(prevEstimated.id, RideRecord.STATUS_CONCLUIDO)
            if (wasConsecutiveOffer) {
                previousConsecutiveRideId = prevEstimated.id
                lastConsecutiveAcceptTimestamp = now
            }
            diag("AUTO-COMPLETE CORRIDA ANTERIOR [#${prevEstimated.id} - ${prevEstimated.platform}]: R$ ${prevEstimated.grossPrice} concluída ao aceitar nova corrida")
        }

        // Ao aceitar a corrida, se houver endereço, mantém o overlay por mais 8 segundos com o Street View aberto pro motorista ver a fachada!
        BubbleOverlayService.onRideAcceptedShowStreetViewBriefly(this, offerToSave.destinationAddress)

        val finalRideId = if (rideId != null && (db.acceptRide(rideId, evidence) || db.rideExists(rideId))) {
            rideId
        } else {
            // Sem linha de oferta (apagada por fechar/expirar o overlay antes do aceite): grava como aceita.
            val existing = db.findRecentDuplicateRide(
                platform = offerToSave.platform,
                price = offerToSave.price,
                distanceKm = offerToSave.distanceKm,
                withinMs = 60_000L
            )
            if (existing != null) {
                db.acceptRide(existing.id, evidence)
                existing.id
            } else {
                db.insertRide(
                    platform = offerToSave.platform,
                    gross = offerToSave.price,
                    distance = offerToSave.distanceKm,
                    net = offerToSave.netProfit,
                    status = RideRecord.STATUS_ESTIMADO,
                    originalPrice = offerToSave.price,
                    acceptanceEvidence = evidence
                )
            }
        }


        diag("CORRIDA ACEITA E CONFIRMADA [#${finalRideId} - ${offerToSave.platform}]: R$ ${offerToSave.price} (${offerToSave.distanceKm} km) | evidence=$evidence")

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
        offerArrivedDuringRide = false
        offerArrivedDuringFreeGps = false
        // IMPORTANTE: se o motorista recusou uma oferta consecutiva durante uma viagem em andamento,
        // a viagem atual continua ativa (NÃO zerar isRideCurrentlyActive).
        declineCooldownUntil = now + 2500L
        BubbleOverlayService.stop(this)
        diag("Corrida recusada pelo motorista. Oferta descartada do banco e overlay fechado (rideActive=$isRideCurrentlyActive).")
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

        lastDemandNavigationTimestamp = 0L

        // IMPORTANTE: NUNCA zerar isRideCurrentlyActive aqui!
        // Quando uma oferta consecutiva toca durante uma viagem em andamento, a corrida atual continua ativa.
        // Manter isRideCurrentlyActive = true garante que:
        //  1) uma 2ª ou 3ª oferta consecutiva na mesma viagem também receba offerArrivedDuringRide = true;
        //  2) os marcadores de desembarque da corrida atual ("Corrida em dinheiro" / "O passageiro pode gravar")
        //     NÃO promovam a oferta consecutiva ignorada/recusada;
        //  3) o recibo de encerramento da corrida atual seja aceito pelo ScreenRouter.
        val isNewOfferCard = pendingOffer == null ||
                pendingOffer?.platform != offer.platform ||
                Math.abs((pendingOffer?.price ?: 0.0) - offer.price) >= 0.01
        if (isNewOfferCard) {
            offerArrivedDuringRide = isRideCurrentlyActive
            offerArrivedDuringFreeGps = !isRideCurrentlyActive &&
                lastFreeGpsSeenTimestamp != 0L &&
                (timestamp - lastFreeGpsSeenTimestamp) in 0..15_000L
        }
        idleSeenSince = 0L

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

            val offerWithHome = if (prefs.isModoCasaActive) {
                val eval = ModoCasaHelper.evaluateRideTowardsHome(this, offer.destinationAddress)
                offer.copy(
                    isHeadingHome = eval.isHeadingHome,
                    homeDistanceDeltaKm = eval.deltaKm
                )
            } else {
                offer
            }

            val finalOffer = if (prefs.blacklistEnabled) {
                val blacklistItems = db.getAllBlacklistItems()
                BlacklistManager.evaluateOffer(offerWithHome, blacklistItems)
            } else {
                offerWithHome
            }

            diag("OFERTA DETECTADA [${finalOffer.platform}]: R$ ${finalOffer.price} | ${finalOffer.distanceKm}km | R$/km ${finalOffer.ratePerKm} | Lucro R$ ${finalOffer.netProfit} | ModoCasa: ${finalOffer.isHeadingHome} (${finalOffer.homeDistanceDeltaKm}km) | duringRide=$offerArrivedDuringRide")

            // 1. Registra a oferta como OFERTA (oculta do faturamento). Só vira ESTIMADO quando o motorista ACEITAR.
            val existingRide = db.findRecentDuplicateRide(finalOffer.platform, finalOffer.price, finalOffer.distanceKm)
            val rideId = existingRide?.id ?: db.insertRide(
                platform = finalOffer.platform,
                gross = finalOffer.price,
                distance = finalOffer.distanceKm,
                net = finalOffer.netProfit,
                status = RideRecord.STATUS_OFERTA,
                originalPrice = finalOffer.price,
                acceptanceEvidence = RideRecord.EVIDENCE_OFFER
            )

            // Guarda como oferta pendente associada ao ID
            setPendingOffer(finalOffer, timestamp, rideId)

            // Atualiza Dashboard / Card Última Corrida
            sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })

            // 2. Dispara Bolha/Overlay e Notificação diretamente na tela
            BubbleOverlayService.showOffer(this, finalOffer, rideId)

            // 3. Processa Lógica de Aceite / Recusa Automática (inclusive para apps canvas sem nós)
            handleAutomation(finalOffer, clickableNodes, timestamp, rideId)
        }
    }

    private fun handleReceipt(receipt: ReceiptResult) {
        val now = System.currentTimeMillis()
        val hasConsecutiveOfferWaiting = pendingOffer != null &&
            offerArrivedDuringRide &&
            (now - pendingOfferTimestamp) in 0..120_000L
        val hasAlreadyAcceptedConsecutiveRide = previousConsecutiveRideId != -1L &&
            (now - lastConsecutiveAcceptTimestamp) in 0..180_000L

        if (hasConsecutiveOfferWaiting) {
            // A corrida anterior acabou de encerrar enquanto uma oferta consecutiva recente estava pendente.
            // Mantém offerArrivedDuringRide = true e marca activeRideSeenDropoffPhase = true para que um card
            // consecutivo IGNORADO não seja promovido por marcadores residuais de desembarque ou GPS genérico,
            // mas um card consecutivo ACEito no canvas da 99 seja promovido assim que entrar na fase de embarque
            // (isPickupPhaseScreen) ou casar explicitamente o endereço no HUD de GPS.
            offerArrivedDuringRide = true
            activeRideSeenDropoffPhase = true
            pendingOfferTimestamp = now
            isRideCurrentlyActive = false
        } else if (hasAlreadyAcceptedConsecutiveRide) {
            // O motorista já havia aceitado a corrida consecutiva pelo overlay durante o fim da viagem anterior:
            // mantém isRideCurrentlyActive = true para a nova corrida consecutiva.
            isRideCurrentlyActive = true
        } else {
            isRideCurrentlyActive = false
            pendingOffer = null
            pendingOfferRideId = null
            BubbleOverlayService.stop(this)
        }

        // Seleciona a corrida alvo do recibo:
        // 1. Se houve corrida consecutiva já aceita pelo overlay, o recibo pertence à corrida ANTERIOR (previousConsecutiveRideId).
        // 2. Caso contrário, busca a corrida ESTIMADO mais recente da plataforma.
        // 3. Se a corrida já foi marcada como CONCLUIDO há poucos segundos por um modal intermediário
        //    (ex: "Pagamento recebido!" ou "Como foi sua corrida" sem preço explícito) e logo em seguida
        //    apareceu o toast "Valor da corrida | +R$17,80", permite atualizar o valor final dessa mesma corrida.
        val ride: RideRecord = when {
            hasAlreadyAcceptedConsecutiveRide -> {
                val prev = db.getRideById(previousConsecutiveRideId)
                previousConsecutiveRideId = -1L
                prev ?: db.getLatestEstimatedRide(receipt.platform, maxAgeMs = 4 * 60 * 60 * 1000L) ?: return
            }
            else -> {
                val latestEst = db.getLatestEstimatedRide(receipt.platform, maxAgeMs = 4 * 60 * 60 * 1000L)
                if (latestEst != null) {
                    latestEst
                } else if (lastHandledRideId != -1L && (now - lastReceiptProcessedTimestamp) in 0..90_000L) {
                    val recentHandled = db.getRideById(lastHandledRideId)
                    if (recentHandled != null && recentHandled.platform.equals(receipt.platform, ignoreCase = true)) {
                        recentHandled
                    } else {
                        return
                    }
                } else {
                    return
                }
            }
        }

        if (ride.id == lastHandledRideId && ride.status != RideRecord.STATUS_ESTIMADO) {
            // Já processamos o fechamento desta corrida: só atualiza se este novo recibo trouxer um valor final
            // explícito diferente (ou se a corrida havia sido auto-concluída no retorno ao ocioso e chegou um cancelamento imediato).
            if (receipt.isCompleted) {
                val explicitNewPrice = receipt.finalPrice ?: return
                val effectivePrice = when {
                    receipt.isCashCollectionScreen -> Math.max(explicitNewPrice, ride.originalPrice)
                    !ParsingEngine.isPlausibleFinalFare(explicitNewPrice, ride.originalPrice) -> {
                        diag("FARE_REJECTED complementar: R$ $explicitNewPrice fora da faixa da oferta R$ ${ride.originalPrice}; mantendo original")
                        ride.originalPrice
                    }
                    else -> explicitNewPrice
                }
                if (Math.abs(ride.grossPrice - effectivePrice) < 0.01) return
                val cost = ride.distanceKm * prefs.costPerKm
                val newNet = Math.max(0.0, Math.round((effectivePrice - cost) * 100.0) / 100.0)
                db.updateRidePrice(
                    ride.id,
                    effectivePrice,
                    newNet,
                    RideRecord.STATUS_CONCLUIDO,
                    RideRecord.EVIDENCE_RECEIPT
                )
                driverCancellationPromptAt = 0L
                lastReceiptProcessedTimestamp = now
                diag("RECIBO COMPLEMENTAR [${receipt.platform}]: corrida #${ride.id} atualizada para R$ $effectivePrice")
                sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                    setPackage(this@RotaAccessibilityService.packageName)
                })
            } else if (
                receipt.isCancelled &&
                ride.id == lastAutoCompletedIdleRideId &&
                (now - lastAutoCompletedIdleTimestamp) <= 20_000L
            ) {
                db.updateRideStatus(
                    ride.id,
                    receipt.status,
                    receipt.cancellationFee ?: 0.0,
                    if ((receipt.cancellationFee ?: 0.0) > 0.0) RideRecord.EVIDENCE_EXPLICIT_FEE else RideRecord.EVIDENCE_CANCEL
                )
                driverCancellationPromptAt = 0L
                lastAutoCompletedIdleRideId = -1L
                lastReceiptProcessedTimestamp = now
                diag("RECIBO DE CANCELAMENTO TARDIO [${receipt.platform}]: corrida #${ride.id} ajustada para ${receipt.status}")
                sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                    setPackage(this@RotaAccessibilityService.packageName)
                })
            }
            return
        }

        lastHandledRideId = ride.id
        lastReceiptProcessedTimestamp = now

        diag("RECIBO DETECTADO [${receipt.platform}]: ride=#${ride.id}, finalPrice=${receipt.finalPrice}, cashScreen=${receipt.isCashCollectionScreen}, cancelado=${receipt.isCancelled}")

        if (receipt.isCompleted) {
            val rawFinal = receipt.finalPrice
            val finalPrice = when {
                rawFinal == null -> ride.originalPrice
                receipt.isCashCollectionScreen -> Math.max(rawFinal, ride.originalPrice)
                !ParsingEngine.isPlausibleFinalFare(rawFinal, ride.originalPrice) -> {
                    diag("FARE_REJECTED: R$ $rawFinal fora da faixa da oferta R$ ${ride.originalPrice}; mantendo original")
                    ride.originalPrice
                }
                else -> rawFinal
            }
            val cost = ride.distanceKm * prefs.costPerKm
            val newNet = Math.max(0.0, Math.round((finalPrice - cost) * 100.0) / 100.0)
            db.updateRidePrice(
                ride.id,
                finalPrice,
                newNet,
                RideRecord.STATUS_CONCLUIDO,
                RideRecord.EVIDENCE_RECEIPT
            )
            driverCancellationPromptAt = 0L
        } else if (receipt.isCancelled) {
            db.updateRideStatus(
                ride.id,
                receipt.status,
                receipt.cancellationFee ?: 0.0,
                if ((receipt.cancellationFee ?: 0.0) > 0.0) RideRecord.EVIDENCE_EXPLICIT_FEE else RideRecord.EVIDENCE_CANCEL
            )
            driverCancellationPromptAt = 0L
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
        // BLOQUEIO ESTREITO DE LISTA NEGRA: se a corrida possui embarque ou destino na lista negra,
        // o aceite automático é estritamente impedido.
        if (offer.isBlacklistBlocked) {
            diag("AUTO-ACCEPT BLOQUEADO: Oferta com bairro na Lista Negra (${offer.blacklistAlert})")
            if (prefs.autoDeclineEnabled) {
                val declined = performClickDecline(clickableNodes)
                if (declined) {
                    clearPendingOfferAndDismissOverlay(timestamp)
                }
            }
            return
        }

        val matchesConditions = (offer.ratePerKm >= prefs.minRatePerKm) &&
                (offer.distanceKm <= prefs.maxDistanceKm)

        // REGRA DE SEGURANÇA: Se a nota do passageiro for baixa, NUNCA aceita automático
        if (offer.isLowRating) {
            return
        }

        if (prefs.autoAcceptEnabled && matchesConditions) {
            val acceptedByNode = performClickAccept(clickableNodes) || clickAcceptInAnyWindow()
            if (acceptedByNode) {
                diag("AUTO-ACCEPT OK: clique por nó executado")
                commitPendingOfferAsAccepted(timestamp, RideRecord.EVIDENCE_CLICK)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                diag("AUTO-ACCEPT: sem nó acessível -> tentando botão Aceitar via OCR/gesto")
                performTapOrSwipeAcceptWithVerification(offer, fromOverlayTouch = false)
            } else if (prefs.isMotorcycleMode) {
                dispatchSwipeAcceptGesture { dispatched ->
                    if (dispatched) commitPendingOfferAsAccepted(timestamp, RideRecord.EVIDENCE_GESTURE)
                }
            }
        } else if (prefs.autoDeclineEnabled && !matchesConditions) {
            // Recusa automática se não bateu os critérios
            val declined = performClickDecline(clickableNodes)
            if (declined) {
                clearPendingOfferAndDismissOverlay(timestamp)
            }
        }
    }

    /**
     * Dispara o aceite manual de corrida solicitado via toque no overlay flutuante.
     * Tenta nó acessível -> botão "Aceitar" via OCR (inclusive no Modo Moto) -> swipe slider (se Modo Moto).
     */
    fun triggerManualAccept() {
        diag("MANUAL ACCEPT TRIGGERED VIA OVERLAY TOUCH")
        val now = System.currentTimeMillis()

        if (pendingOffer == null) {
            BubbleOverlayService.activeOverlayOffer?.let {
                setPendingOffer(it, now, BubbleOverlayService.activeOverlayRideId)
            }
        }
        val offer = pendingOffer
        if (offer == null) {
            finishTapOrSwipeAccept(success = false, reason = "sem oferta pendente", fromOverlayTouch = true)
            return
        }
        if (isAcceptGestureInFlight) {
            diag("MANUAL ACCEPT ignorado: já existe tentativa em andamento")
            return
        }
        // Mantém a oferta elegível e o overlay protegido contra auto-dismiss durante a verificação.
        setPendingOffer(offer, now, pendingOfferRideId)
        BubbleOverlayService.extendAutoDismissForManualAccept(12_000L)

        // 1. Clique por nó de acessibilidade (Uber e telas nativas, inclusive em Modo Moto).
        if (clickAcceptInAnyWindow()) {
            diag("MANUAL ACCEPT: clique por nó de acessibilidade executado")
            finishTapOrSwipeAccept(success = true, reason = "clique em nó", fromOverlayTouch = true)
            return
        }

        // 2. Canvas Flutter (99): esconde o overlay, localiza "Aceitar" via OCR e só salva se verificar transição.
        performTapOrSwipeAcceptWithVerification(offer, fromOverlayTouch = true)
    }

    private fun performTapOrSwipeAcceptWithVerification(offer: RideOffer, fromOverlayTouch: Boolean) {
        if (isAcceptGestureInFlight) return
        isAcceptGestureInFlight = true
        val modeLabel = if (fromOverlayTouch) "MANUAL ACCEPT" else "AUTO ACCEPT"

        BubbleOverlayService.extendAutoDismissForManualAccept(12_000L)
        BubbleOverlayService.setPassThrough(this, true)
        ocrHandler.postDelayed({
            captureScreenLines { lines ->
                val lineTexts = lines?.joinToString(" | ") { it.text }?.take(350) ?: "null"
                diag("$modeLabel OCR pre-tap lines=${lines?.size ?: -1} :: $lineTexts")

                val target = lines
                    ?.filter { ParsingEngine.isAcceptActionText(it.text) && it.bounds != null }
                    ?.maxByOrNull { it.bounds!!.bottom }

                if (target != null) {
                    val b = target.bounds!!
                    diag("$modeLabel: botão '${target.text}' encontrado em (${b.centerX()},${b.centerY()})")
                    dispatchTapGesture(b.centerX().toFloat(), b.centerY().toFloat()) { dispatched ->
                        ocrHandler.post {
                            if (dispatched) verifyAcceptanceAfterGesture(offer, 0, fromOverlayTouch)
                            else finishTapOrSwipeAccept(false, "toque não executado", fromOverlayTouch)
                        }
                    }
                } else if (prefs.isMotorcycleMode) {
                    diag("$modeLabel: botão Aceitar não encontrado no OCR -> fallback swipe (Modo Moto)")
                    dispatchSwipeAcceptGesture { dispatched ->
                        ocrHandler.post {
                            if (dispatched) verifyAcceptanceAfterGesture(offer, 0, fromOverlayTouch)
                            else finishTapOrSwipeAccept(false, "gesto de deslizar não executado", fromOverlayTouch)
                        }
                    }
                } else {
                    ocrHandler.post {
                        finishTapOrSwipeAccept(false, "botão Aceitar não encontrado na tela", fromOverlayTouch)
                    }
                }
            }
        }, 220L)
    }

    private fun collectCurrentWindowTexts(): List<String> {
        val texts = ArrayList<String>()
        val clickables = ArrayList<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString() ?: ""
                if (!pkg.contains("com.drifly.app", ignoreCase = true)) {
                    traverseNodes(root, texts, clickables, 0)
                }
            }
        } catch (_: Exception) {}
        return texts
    }

    /**
     * Depois do toque/deslize, confere via OCR + acessibilidade se a corrida realmente foi aceita.
     * Só promove para ESTIMADO se houver confirmação real (ActiveRide / transição de viagem).
     */
    private fun verifyAcceptanceAfterGesture(offer: RideOffer, attempt: Int, fromOverlayTouch: Boolean) {
        ocrHandler.postDelayed({
            captureScreenLines { lines ->
                ocrHandler.post {
                    val ocrTexts = lines?.map { it.text } ?: emptyList()
                    val nativeTexts = collectCurrentWindowTexts()
                    val combinedTexts = (ocrTexts + nativeTexts).distinct()
                    val effectiveTexts = if (combinedTexts.isNotEmpty()) combinedTexts else null

                    val reading = if (!effectiveTexts.isNullOrEmpty()) {
                        ScreenRouter.read(
                            packageName = lastSupportedPackage,
                            texts = effectiveTexts,
                            costPerKm = prefs.costPerKm,
                            minRatingThreshold = prefs.minPassengerRating,
                            isRideCurrentlyActive = isRideCurrentlyActive
                        )
                    } else {
                        ScreenReading.Unknown
                    }

                    val outcome = AcceptanceRules.classifyPostTapVerification(
                        texts = effectiveTexts,
                        offerPrice = offer.price,
                        isIdleScreen = !effectiveTexts.isNullOrEmpty() && ParsingEngine.isIdleDriverScreen(effectiveTexts),
                        isEarningsPanel = !effectiveTexts.isNullOrEmpty() && ParsingEngine.isEarningsPanelScreen(effectiveTexts),
                        isActiveRide = reading is ScreenReading.ActiveRide,
                        isDemandHotspot = !effectiveTexts.isNullOrEmpty() && ParsingEngine.isDemandHotspotScreen(effectiveTexts),
                        stillSameOffer = reading is ScreenReading.Offer && Math.abs(reading.offer.price - offer.price) < 0.01,
                        attempt = attempt,
                        maxAttempts = 2,
                        emptyThreshold = OCR_EMPTY_TEXT_THRESHOLD
                    )

                    diag(
                        "VERIFY_ACCEPT attempt=$attempt outcome=$outcome reading=${reading::class.simpleName} " +
                            "n=${effectiveTexts?.size ?: 0} :: ${effectiveTexts?.joinToString(" | ")?.take(250) ?: "null"}"
                    )

                    when (outcome) {
                        AcceptanceRules.PostTapOutcome.CONFIRMED ->
                            finishTapOrSwipeAccept(true, "aceite verificado (${reading::class.simpleName})", fromOverlayTouch)
                        AcceptanceRules.PostTapOutcome.FAILED ->
                            finishTapOrSwipeAccept(false, "card ainda na tela, expirou ou voltou ao mapa ocioso", fromOverlayTouch)
                        AcceptanceRules.PostTapOutcome.RETRY ->
                            verifyAcceptanceAfterGesture(offer, attempt + 1, fromOverlayTouch)
                    }
                }
            }
        }, 1_300L)
    }

    private fun finishTapOrSwipeAccept(success: Boolean, reason: String, fromOverlayTouch: Boolean) {
        isAcceptGestureInFlight = false
        BubbleOverlayService.setPassThrough(this, false)
        val label = if (fromOverlayTouch) "MANUAL ACCEPT" else "AUTO ACCEPT"
        if (success) {
            diag("$label OK ($reason)")
            val evidence = if (fromOverlayTouch) RideRecord.EVIDENCE_OVERLAY_TAP else RideRecord.EVIDENCE_GESTURE
            commitPendingOfferAsAccepted(System.currentTimeMillis(), evidence)
        } else {
            diag("$label FALHOU ($reason) -> nada salvo no Faturamento")
            BubbleOverlayService.isAcceptTriggered = false
            BubbleOverlayService.restoreAutoDismissAfterFailedAccept()
            if (fromOverlayTouch) {
                android.widget.Toast.makeText(
                    this,
                    "DriFly não conseguiu aceitar. Aceite direto no app.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class OcrLine(val text: String, val bounds: android.graphics.Rect?)

    private fun captureScreenLines(onResult: (List<OcrLine>?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                val buffer = screenshotResult.hardwareBuffer
                                val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshotResult.colorSpace)
                                val bitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                buffer.close()
                                if (bitmap == null) {
                                    onResult(null)
                                    return@launch
                                }
                                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                    .process(InputImage.fromBitmap(bitmap, 0))
                                    .addOnSuccessListener { visionText ->
                                        val out = ArrayList<OcrLine>()
                                        for (block in visionText.textBlocks) {
                                            for (line in block.lines) {
                                                val t = line.text.trim()
                                                if (t.isNotBlank()) out.add(OcrLine(t, line.boundingBox))
                                            }
                                        }
                                        onResult(out)
                                    }
                                    .addOnFailureListener { onResult(null) }
                                    .addOnCompleteListener { bitmap.recycle() }
                            } catch (e: Exception) {
                                Log.w(TAG, "Erro no OCR do aceite manual", e)
                                onResult(null)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao capturar tela para o aceite manual", e)
            onResult(null)
        }
    }

    private fun dispatchTapGesture(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, null)
        if (!accepted) onDone(false)
    }

    private fun dispatchSwipeAcceptGesture(onDone: (Boolean) -> Unit) {
        val m = resources.displayMetrics
        val path = Path().apply {
            moveTo(m.widthPixels * 0.2f, m.heightPixels * 0.78f)
            lineTo(m.widthPixels * 0.85f, m.heightPixels * 0.78f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
        }, null)
        if (!accepted) onDone(false)
    }

    /** Procura o botão Aceitar em todas as janelas (exceto o DriFly) e clica no nó clicável correspondente. */
    private fun clickAcceptInAnyWindow(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            windows?.forEach { w -> w.root?.let { r -> if (roots.none { it == r }) roots.add(r) } }
        } catch (_: Exception) {}
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: ""
            if (pkg.contains("com.drifly.app", ignoreCase = true)) continue
            if (clickAcceptNode(root, 0)) return true
        }
        return false
    }

    private fun clickAcceptNode(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 30) return false
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if (ParsingEngine.isAcceptActionText(text) || ParsingEngine.isAcceptActionText(id)) {
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !target.isClickable && hops < 4) {
                target = target.parent
                hops++
            }
            if (target != null && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (clickAcceptNode(child, depth + 1)) return true
        }
        return false
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
                                                if (ParsingEngine.isDemandHotspotScreen(ocrTexts)) {
                                                    lastDemandNavigationTimestamp = ocrNow
                                                } else if (ParsingEngine.isIdleDriverScreen(ocrTexts) && !isRideCurrentlyActive) {
                                                    lastDemandNavigationTimestamp = 0L
                                                }
                                                when (val reading = ScreenRouter.read(
                                                    packageName = packageName,
                                                    texts = ocrTexts,
                                                    costPerKm = prefs.costPerKm,
                                                    minRatingThreshold = prefs.minPassengerRating,
                                                    isRideCurrentlyActive = isEffectiveRideActiveForReceipt(ocrNow)
                                                )) {
                                                    is ScreenReading.Receipt -> {
                                                        consecutiveEmptyOcrCount = 0
                                                        ocrFastRetryPending = false
                                                        val receipt = reading.receipt
                                                        diag("BRANCH OCR_RECEIPT ${receipt.platform} completed=${receipt.isCompleted} cancelled=${receipt.isCancelled} price=${receipt.finalPrice}")
                                                        val currentReceiptHash = receipt.hashCode()
                                                        if (currentReceiptHash != lastReceiptHash || (ocrNow - lastReceiptProcessedTimestamp) > 8_000L) {
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
                                                        handleTransitionToActiveRide(ocrNow, ocrTexts, packageName)
                                                    }
                                                    is ScreenReading.Unknown -> {
                                                        diag("OCR_PARSE_NULL (texto lido, mas sem preço+distância válidos)")
                                                        onUnknownReading(
                                                            packageName = packageName,
                                                            texts = ocrTexts,
                                                            clickableNodes = clickableNodes,
                                                            now = ocrNow,
                                                            allowHybridOcr = false
                                                        )
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
        if (instance === this) {
            instance = null
        }
        ocrHandler.removeCallbacks(ocrWatchdogRunnable)
        serviceScope.cancel()
    }

    companion object {
        @Volatile
        var instance: RotaAccessibilityService? = null

        private const val TAG = "RotaAccessibilityService"
        private const val TAG_DIAG = "DriFlyDiag"
        private const val DIAG_ENABLED = true
        const val ACTION_RIDE_UPDATED = "com.drifly.app.ACTION_RIDE_UPDATED"
        private const val PENDING_OFFER_VALIDITY_MS = 45_000L
        private const val ACCEPTED_STREET_VIEW_GRACE_MS = 8_500L
        private const val MIN_ACTIVE_RIDE_DURATION_FOR_IDLE_COMPLETION_MS = 90_000L

        // Janela em que a MESMA oferta (mesmo preço e distância) não é exibida de novo pelo overlay.
        // Precisa cobrir a duração do card na tela (~15-20s), senão o overlay reaparece durante a oferta.
        private const val SAME_OFFER_SUPPRESS_MS = 20_000L

        // Intervalo mínimo entre capturas de tela + OCR (evita custo excessivo de CPU/bateria).
        // O OCR "quente" leva ~400ms no dispositivo de referência, então 1200ms mantém o custo baixo.
        private const val OCR_MIN_INTERVAL_MS = 1_200L

        // Intervalo de OCR híbrido quando há oferta pendente ou HUD nativo de GPS sem camada Flutter.
        private const val OCR_HYBRID_INTERVAL_MS = 2_500L

        // Janela após abrir "Navegar" num ponto de demanda para evitar falso positivo no HUD livre de GPS.
        private const val DEMAND_NAV_GRACE_MS = 10 * 60 * 1000L

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
            val isNewOffer = pendingOffer == null ||
                pendingOffer?.platform != offer.platform ||
                Math.abs((pendingOffer?.price ?: 0.0) - offer.price) >= 0.01
            if (isNewOffer) {
                instance?.let { svc ->
                    svc.offerArrivedDuringRide = isRideCurrentlyActive
                    svc.offerArrivedDuringFreeGps = !isRideCurrentlyActive &&
                        svc.lastFreeGpsSeenTimestamp != 0L &&
                        (timestamp - svc.lastFreeGpsSeenTimestamp) in 0..15_000L
                }
            }
            pendingOffer = offer
            pendingOfferTimestamp = timestamp
            pendingOfferRideId = rideId
        }

        fun consumeRecentlyAutoCompletedIdleRideId(
            now: Long = System.currentTimeMillis(),
            maxAgeMs: Long = 20_000L
        ): Long? {
            val svc = instance ?: return null
            val rideId = svc.lastAutoCompletedIdleRideId
            val ts = svc.lastAutoCompletedIdleTimestamp
            if (rideId != -1L && ts != 0L && (now - ts) in 0..maxAgeMs) {
                svc.lastAutoCompletedIdleRideId = -1L
                return rideId
            }
            return null
        }
    }
}
