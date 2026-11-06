package com.drifly.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    private lateinit var prefs: PreferencesManager
    private lateinit var db: DatabaseHelper

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        db = DatabaseHelper(this)
        try {
            Log.i(TAG, "RotaAccessibilityService inicializado com sucesso.")
        } catch (ignored: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.isMonitoringActive) return

        val packageName = event.packageName?.toString() ?: return
        // Ignora completamente qualquer evento vindo do próprio DriFly
        if (packageName.contains("com.drifly.app", ignoreCase = true)) return
        if (!isSupportedPackageName(packageName)) return

        val now = System.currentTimeMillis()

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

        serviceScope.launch {
            try {
                // Coleta raízes em background sem travar a UI thread do sistema
                val candidateRoots = ArrayList<AccessibilityNodeInfo>()
                try {
                    event.source?.let { src ->
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

                if (extractedTexts.isNotEmpty()) {
                    // 1. Verifica se o motorista entrou em corrida ativa / navegação
                    if (ParsingEngine.isActiveRideOrNavigationScreen(extractedTexts)) {
                        handleTransitionToActiveRide(now)
                        return@launch
                    }

                    // 2. Se estamos em corrida ativa, verificamos apenas se chegou na tela de recibo/conclusão ou cancelamento
                    if (isRideCurrentlyActive) {
                        val receipt = ParsingEngine.parseReceipt(packageName, extractedTexts)
                        if (receipt != null) {
                            val currentReceiptHash = receipt.hashCode()
                            if (currentReceiptHash != lastReceiptHash && (now - lastReceiptProcessedTimestamp) > 8_000L) {
                                lastReceiptHash = currentReceiptHash
                                lastReceiptTimestamp = now
                                handleReceipt(receipt)
                            }
                        }
                        return@launch
                    }

                    // 3. Tenta extrair oferta de corrida
                    val offer = ParsingEngine.parseNodes(
                        packageName,
                        extractedTexts,
                        prefs.costPerKm,
                        prefs.minPassengerRating
                    )

                    if (offer != null) {
                        processDetectedOffer(offer, clickableNodes, now)
                    } else {
                        // Verifica se a tela é de conclusão de corrida ou de cancelamento
                        val receipt = ParsingEngine.parseReceipt(packageName, extractedTexts)
                        if (receipt != null) {
                            val currentReceiptHash = receipt.hashCode()
                            if (currentReceiptHash != lastReceiptHash && (now - lastReceiptProcessedTimestamp) > 8_000L) {
                                lastReceiptHash = currentReceiptHash
                                lastReceiptTimestamp = now
                                handleReceipt(receipt)
                            }
                        }
                    }
                } else if (!isRideCurrentlyActive &&
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (now - lastScreenshotTimestamp > 4000L) &&
                    !BubbleOverlayService.isRunning()
                ) {
                    // OCR fallback SOMENTE quando uma janela nova abre, a árvore veio vazia, não há corrida ativa e o overlay não está na tela
                    lastScreenshotTimestamp = now
                    performOcrFallback(packageName, clickableNodes)
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
        } else if (BubbleOverlayService.isRunning()) {
            BubbleOverlayService.stop(this)
        }
        isRideCurrentlyActive = true
    }

    private fun commitPendingOfferAsAccepted(now: Long) {
        val offerToSave = pendingOffer ?: return
        if ((now - pendingOfferTimestamp) > PENDING_OFFER_VALIDITY_MS) {
            pendingOffer = null
            return
        }

        // Consome a oferta pendente para nunca salvar duplicado
        pendingOffer = null
        isRideCurrentlyActive = true
        declineCooldownUntil = now + 2500L

        // Ao aceitar a corrida, se houver endereço, mantém o overlay por mais 8 segundos com o Street View aberto pro motorista ver a fachada!
        BubbleOverlayService.onRideAcceptedShowStreetViewBriefly(this, offerToSave.destinationAddress)

        val insertedId = db.insertRide(
            platform = offerToSave.platform,
            gross = offerToSave.price,
            distance = offerToSave.distanceKm,
            net = offerToSave.netProfit,
            status = RideRecord.STATUS_ESTIMADO,
            originalPrice = offerToSave.price
        )

        try {
            Log.i(TAG, "CORRIDA ACEITA E SALVA [#${insertedId} - ${offerToSave.platform}]: R$ ${offerToSave.price} (${offerToSave.distanceKm} km)")
        } catch (ignored: Throwable) {}

        sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
            setPackage(this@RotaAccessibilityService.packageName)
        })
    }

    private fun clearPendingOfferAndDismissOverlay(now: Long) {
        pendingOffer = null
        isRideCurrentlyActive = false
        declineCooldownUntil = now + 2500L
        BubbleOverlayService.stop(this)
        try {
            Log.i(TAG, "Corrida recusada pelo motorista. Oferta descartada sem salvar no banco e overlay fechado.")
        } catch (ignored: Throwable) {}
    }

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
        if (timestamp < declineCooldownUntil) return

        // Se uma nova oferta legítima tocou na tela, significa que não estamos em viagem bloqueada
        isRideCurrentlyActive = false

        val currentHash = offer.hashCode()
        // Dedup da mesma corrida enquanto ela está tocando na tela (evita reprocessar o mesmo card a cada segundo)
        val existingPending = pendingOffer
        val isSameOfferAsPending = existingPending != null &&
                existingPending.platform == offer.platform &&
                Math.abs(existingPending.price - offer.price) < 0.01 &&
                Math.abs(existingPending.distanceKm - offer.distanceKm) < 0.1

        if (isSameOfferAsPending && (timestamp - lastOfferTimestamp) < 12_000L) {
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

            try {
                Log.i(TAG, "OFERTA DETECTADA [${finalOffer.platform}]: R$ ${finalOffer.price} | ${finalOffer.distanceKm}km | R$/km ${finalOffer.ratePerKm} | Lucro R$ ${finalOffer.netProfit} | ModoCasa: ${finalOffer.isHeadingHome} (${finalOffer.homeDistanceDeltaKm}km)")
            } catch (ignored: Throwable) {}

            // Guarda como oferta pendente (NÃO salva no banco ainda — só salva se aceitar!)
            setPendingOffer(finalOffer, timestamp)

            // Dispara Bolha/Overlay diretamente na tela
            BubbleOverlayService.showOffer(this, finalOffer)

            // Processa Lógica de Aceite / Recusa Automática
            if (clickableNodes.isNotEmpty()) {
                handleAutomation(finalOffer, clickableNodes, timestamp)
            }
        }
    }

    private fun handleReceipt(receipt: ReceiptResult) {
        isRideCurrentlyActive = false
        pendingOffer = null
        BubbleOverlayService.stop(this)

        val ride = db.getLatestEstimatedRide(receipt.platform) ?: return
        if (ride.id == lastHandledRideId) return
        lastHandledRideId = ride.id
        lastReceiptProcessedTimestamp = System.currentTimeMillis()

        try {
            Log.i(TAG, "RECIBO DETECTADO [${receipt.platform}]: finalPrice=${receipt.finalPrice}, cancelado=${receipt.isCancelled}")
        } catch (ignored: Throwable) {}

        if (receipt.isCompleted) {
            if (receipt.finalPrice != null && receipt.finalPrice > 0.0) {
                val cost = ride.distanceKm * prefs.costPerKm
                val newNet = Math.round((receipt.finalPrice - cost) * 100.0) / 100.0
                db.updateRidePrice(ride.id, receipt.finalPrice, newNet, RideRecord.STATUS_CONCLUIDO)
            } else {
                db.updateRideStatus(ride.id, RideRecord.STATUS_CONCLUIDO)
            }
            sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })
        } else if (receipt.isCancelled) {
            db.updateRideStatus(ride.id, receipt.status, receipt.cancellationFee ?: 0.0)
            sendBroadcast(Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })
        }
    }

    private fun handleAutomation(
        offer: RideOffer,
        clickableNodes: List<AccessibilityNodeInfo>,
        timestamp: Long
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
        if (BubbleOverlayService.isRunning() || isRideCurrentlyActive) return
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
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
                                            if (ocrTexts.isNotEmpty() && !BubbleOverlayService.isRunning() && !isRideCurrentlyActive) {
                                                val ocrOffer = ParsingEngine.parseNodes(
                                                    packageName = packageName,
                                                    texts = ocrTexts,
                                                    costPerKm = prefs.costPerKm,
                                                    minRatingThreshold = prefs.minPassengerRating
                                                )
                                                if (ocrOffer != null) {
                                                    processDetectedOffer(ocrOffer, clickableNodes, System.currentTimeMillis())
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            bitmap.recycle()
                                        }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Erro ao processar OCR fallback", e)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "takeScreenshot falhou com código: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao invocar takeScreenshot para OCR", e)
        }
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
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "RotaAccessibilityService"
        const val ACTION_RIDE_UPDATED = "com.drifly.app.ACTION_RIDE_UPDATED"
        private const val PENDING_OFFER_VALIDITY_MS = 45_000L

        @Volatile
        var pendingOffer: RideOffer? = null
            private set

        @Volatile
        var pendingOfferTimestamp: Long = 0L
            private set

        @Volatile
        var isRideCurrentlyActive: Boolean = false

        fun setPendingOffer(offer: RideOffer, timestamp: Long = System.currentTimeMillis()) {
            pendingOffer = offer
            pendingOfferTimestamp = timestamp
        }
    }
}
