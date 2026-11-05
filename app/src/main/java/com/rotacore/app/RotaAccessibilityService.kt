package com.rotacore.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RotaAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastOfferHash: Int = 0
    private var lastOfferTimestamp: Long = 0
    private var lastReceiptHash: Int = 0
    private var lastReceiptTimestamp: Long = 0
    private var lastHandledRideId: Long = -1L
    private var lastReceiptProcessedTimestamp: Long = 0L
    private lateinit var prefs: PreferencesManager
    private lateinit var db: DatabaseHelper

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        db = DatabaseHelper(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!prefs.isMonitoringActive) return

        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        if (now - lastOfferTimestamp < 80) return

        val rootNode = rootInActiveWindow ?: return

        serviceScope.launch {
            val extractedTexts = ArrayList<String>()
            val clickableNodes = ArrayList<AccessibilityNodeInfo>()
            traverseNodes(rootNode, extractedTexts, clickableNodes)

            if (extractedTexts.isNotEmpty()) {
                val offer = ParsingEngine.parseNodes(
                    packageName,
                    extractedTexts,
                    prefs.costPerKm,
                    prefs.minPassengerRating
                )

                if (offer != null) {
                    val currentHash = offer.hashCode()
                    if (currentHash != lastOfferHash || (now - lastOfferTimestamp) > 3000) {
                        lastOfferHash = currentHash
                        lastOfferTimestamp = now

                        // Registra oferta com status ESTIMADO no banco financeiro
                        val insertedRideId = db.insertRide(
                            platform = offer.platform,
                            gross = offer.price,
                            distance = offer.distanceKm,
                            net = offer.netProfit,
                            status = RideRecord.STATUS_ESTIMADO,
                            originalPrice = offer.price
                        )

                        // Dispara Bolha na tela
                        BubbleOverlayService.showOffer(this@RotaAccessibilityService, offer)

                        // Notifica a UI para atualizar dashboard e extrato diário (usando pacote do DriFly)
                        sendBroadcast(android.content.Intent(ACTION_RIDE_UPDATED).apply {
                            setPackage(this@RotaAccessibilityService.packageName)
                        })

                        // Processa Lógica de Aceite / Recusa Automática
                        handleAutomation(offer, clickableNodes, insertedRideId)
                    }
                } else {
                    // Verifica se a tela é de conclusão de corrida ou de cancelamento sem notificação
                    val receipt = ParsingEngine.parseReceipt(packageName, extractedTexts)
                    if (receipt != null) {
                        val currentReceiptHash = receipt.hashCode()
                        // Evita reprocessamento em loop da mesma tela de recibo que degradaria corridas anteriores
                        if (currentReceiptHash != lastReceiptHash && (now - lastReceiptProcessedTimestamp) > 10_000L) {
                            lastReceiptHash = currentReceiptHash
                            lastReceiptTimestamp = now
                            handleReceipt(receipt)
                        }
                    }
                }
            }
        }
    }

    private fun handleReceipt(receipt: ReceiptResult) {
        val ride = db.getLatestEstimatedRide(receipt.platform) ?: return
        if (ride.id == lastHandledRideId) return
        lastHandledRideId = ride.id
        lastReceiptProcessedTimestamp = System.currentTimeMillis()

        if (receipt.isCompleted) {
            if (receipt.finalPrice != null && receipt.finalPrice > 0.0) {
                val cost = ride.distanceKm * prefs.costPerKm
                val newNet = Math.round((receipt.finalPrice - cost) * 100.0) / 100.0
                db.updateRidePrice(ride.id, receipt.finalPrice, newNet, RideRecord.STATUS_CONCLUIDO)
            } else {
                db.updateRideStatus(ride.id, RideRecord.STATUS_CONCLUIDO)
            }
            sendBroadcast(android.content.Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })
        } else if (receipt.isCancelled) {
            db.updateRideStatus(ride.id, receipt.status, receipt.cancellationFee ?: 0.0)
            sendBroadcast(android.content.Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })
        }
    }

    private fun handleAutomation(
        offer: RideOffer,
        clickableNodes: List<AccessibilityNodeInfo>,
        insertedRideId: Long
    ) {
        val matchesConditions = (offer.ratePerKm >= prefs.minRatePerKm) &&
                (offer.distanceKm <= prefs.maxDistanceKm)

        // REGRA DE SEGURANÇA: Se a nota do passageiro for baixa, NUNCA aceita automático
        if (offer.isLowRating) {
            return
        }

        if (prefs.autoAcceptEnabled && matchesConditions) {
            if (prefs.isMotorcycleMode) {
                // Modo Moto: Executa Gesto de Swipe da esquerda para direita
                performSwipeAcceptGesture()
            } else {
                // Modo Carro: Clica no botão de aceitar
                performClickAccept(clickableNodes)
            }
        } else if (prefs.autoDeclineEnabled && !matchesConditions) {
            // Recusa automática se não bateu os critérios
            performClickDecline(clickableNodes)
            // Remove do banco financeiro para não gerar faturamento fantasma de corrida recusada
            db.deleteRide(insertedRideId)
            sendBroadcast(android.content.Intent(ACTION_RIDE_UPDATED).apply {
                setPackage(this@RotaAccessibilityService.packageName)
            })
        }
    }

    private fun performClickAccept(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
            if (text.contains("aceitar") || text.contains("corresponder") || text.contains("pegar")) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
    }

    private fun performClickDecline(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").lowercase()
            val id = node.viewIdResourceName ?: ""
            if (text.contains("recusar") || text == "x" || id.contains("close") || id.contains("dismiss")) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
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

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        texts: ArrayList<String>,
        clickables: ArrayList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (!text.isNullOrBlank()) texts.add(text)
        if (!desc.isNullOrBlank()) texts.add(desc)

        if (node.isClickable) {
            clickables.add(node)
        }

        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNodes(child, texts, clickables)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_RIDE_UPDATED = "com.rotacore.app.ACTION_RIDE_UPDATED"
    }
}
