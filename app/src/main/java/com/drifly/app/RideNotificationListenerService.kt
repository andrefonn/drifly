package com.drifly.app

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

import android.util.Log

data class CancellationEvent(
    val platform: String,
    val isCancelled: Boolean,
    val fee: Double,
    val status: String,
    val reason: String
)

class RideNotificationListenerService : NotificationListenerService() {

    private lateinit var db: DatabaseHelper
    private lateinit var prefs: PreferencesManager
    private var lastOfferHash: Int = 0
    private var lastOfferTimestamp: Long = 0L

    // Cache LRU sincronizado para evitar processamento duplicado de notificações idênticas
    private val processedKeys = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 50
            }
        }
    )

    override fun onCreate() {
        super.onCreate()
        db = DatabaseHelper(this)
        prefs = PreferencesManager(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: ""
        val isSupportedPackage = packageName.contains("ubercab") ||
                packageName.contains("uber") ||
                packageName.contains("app99") ||
                packageName.contains("taxis99") ||
                packageName.contains("didi") ||
                packageName.contains("ifood")

        if (!isSupportedPackage) return

        val extras = sbn.notification?.extras ?: return

        // Deduplica eventos repetidos do mesmo StatusBarNotification (ex: updates de ticker / som)
        val notifKey = sbn.key ?: "${packageName}_${sbn.id}_${sbn.postTime}"
        val now = System.currentTimeMillis()
        val lastSeen = processedKeys[notifKey]
        if (lastSeen != null && (now - lastSeen) < 5_000L) {
            return
        }
        processedKeys[notifKey] = now

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val ticker = sbn.notification?.tickerText?.toString() ?: ""

        val combinedContent = "$title $text $bigText $subText $ticker".trim()

        try {
            Log.d(TAG, "[$packageName] Notificação recebida: '$title' | '$text' | '$bigText' | '$subText' | '$ticker'")
        } catch (ignored: Throwable) {}

        // 1. Verifica Cancelamento de Corrida
        val cancelEvent = parseCancellationNotification(packageName, combinedContent)
        if (cancelEvent != null && cancelEvent.isCancelled) {
            handleCancellationEvent(cancelEvent)
            return
        }

        // 2. Conclusão/pagamento/antecipação: confirma a corrida pendente SEM usar o valor da
        // notificação como tarifa (antecipação/bônus não é o ganho da corrida).
        if (ParsingEngine.isCompletionOrAnticipationNotification(combinedContent)) {
            handleCompletionNotification(packageName)
            return
        }

        // 3. Se o monitoramento ativo estiver ligado, tenta extrair oferta de corrida
        if (prefs.isMonitoringActive) {
            val candidateTexts = mutableListOf<String>()
            if (title.isNotBlank()) candidateTexts.add(title)
            if (text.isNotBlank()) candidateTexts.add(text)
            if (bigText.isNotBlank()) candidateTexts.add(bigText)
            if (subText.isNotBlank()) candidateTexts.add(subText)
            if (ticker.isNotBlank()) candidateTexts.add(ticker)

            if (candidateTexts.isNotEmpty() && ParsingEngine.isLikelyRideOfferNotification(candidateTexts)) {
                val offer = ParsingEngine.parseNodes(
                    packageName = packageName,
                    texts = candidateTexts,
                    costPerKm = prefs.costPerKm,
                    minRatingThreshold = prefs.minPassengerRating
                )

                if (offer != null) {
                    val currentHash = offer.hashCode()
                    if (currentHash != lastOfferHash || (now - lastOfferTimestamp) > 4000L) {
                        lastOfferHash = currentHash
                        lastOfferTimestamp = now

                        try {
                            Log.i(TAG, "OFERTA VIA NOTIFICAÇÃO [${offer.platform}]: R$ ${offer.price} | ${offer.distanceKm}km | Lucro R$ ${offer.netProfit}")
                        } catch (ignored: Throwable) {}

                        // Evita duplicar se o accessibility service já inseriu
                        val existingRide = db.findRecentDuplicateRide(offer.platform, offer.price, offer.distanceKm)
                        val rideId = existingRide?.id ?: db.insertRide(
                            platform = offer.platform,
                            gross = offer.price,
                            distance = offer.distanceKm,
                            net = offer.netProfit,
                            status = RideRecord.STATUS_OFERTA,
                            originalPrice = offer.price,
                            acceptanceEvidence = RideRecord.EVIDENCE_OFFER
                        )

                        // Armazena como oferta pendente associada ao ID registrado
                        RotaAccessibilityService.setPendingOffer(offer, now, rideId)

                        // Dispara flutuante e notificação na tela
                        BubbleOverlayService.showOffer(this, offer, rideId)

                        sendBroadcast(Intent(RotaAccessibilityService.ACTION_RIDE_UPDATED).apply {
                            setPackage(this@RideNotificationListenerService.packageName)
                        })
                    }
                }
            }
        }
    }

    private fun handleCompletionNotification(packageName: String) {
        val platform = ParsingEngine.resolvePlatform(packageName)
        if (platform == "CORRIDA") return
        val ride = db.getLatestEstimatedRide(platform, maxAgeMs = MAX_COMPLETION_RIDE_AGE_MS) ?: return
        // A notificação de pagamento/antecipação NÃO é a tarifa: preserva o valor original da oferta.
        // A tela de recibo, quando disponível, é quem pode corrigir o valor depois.
        val cost = ride.distanceKm * prefs.costPerKm
        val net = Math.max(0.0, Math.round((ride.originalPrice - cost) * 100.0) / 100.0)
        db.updateRidePrice(
            ride.id,
            ride.originalPrice,
            net,
            RideRecord.STATUS_CONCLUIDO,
            RideRecord.EVIDENCE_NOTIFICATION
        )
        try {
            Log.i(TAG, "CONCLUSAO VIA NOTIFICACAO [${ride.platform}]: corrida #${ride.id} confirmada com valor original R$ ${ride.originalPrice}")
        } catch (ignored: Throwable) {}
        sendBroadcast(Intent(RotaAccessibilityService.ACTION_RIDE_UPDATED).apply {
            setPackage(this@RideNotificationListenerService.packageName)
        })
    }

    private fun handleCancellationEvent(event: CancellationEvent) {
        // Busca a corrida estimada ativa (máx 30 min) estritamente da mesma plataforma da notificação.
        // Se a corrida acabou de ser auto-concluída nos últimos 20s ao voltar para a tela ociosa,
        // também permite reclassificá-la como cancelada.
        val latestEstimated = db.getLatestEstimatedRide(event.platform, maxAgeMs = MAX_CANCELLATION_RIDE_AGE_MS)
        val targetRide = latestEstimated
            ?: RotaAccessibilityService.consumeRecentlyAutoCompletedIdleRideId()?.let { recentId ->
                val r = db.getRideById(recentId)
                if (r != null && r.platform.equals(event.platform, ignoreCase = true)) r else null
            }

        if (targetRide != null) {
            db.updateRideStatus(
                targetRide.id,
                event.status,
                event.fee,
                if (event.fee > 0.0) RideRecord.EVIDENCE_EXPLICIT_FEE else RideRecord.EVIDENCE_CANCEL
            )

            val intent = Intent(ACTION_RIDE_CANCELLED_DETECTED).apply {
                putExtra("EXTRA_RIDE_ID", targetRide.id)
                putExtra("EXTRA_PLATFORM", event.platform)
                putExtra("EXTRA_STATUS", event.status)
                putExtra("EXTRA_FEE", event.fee)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }
    }

    companion object {
        private const val TAG = "RideNotificationListener"
        const val ACTION_RIDE_CANCELLED_DETECTED = "com.drifly.app.ACTION_RIDE_CANCELLED"
        private const val MAX_CANCELLATION_RIDE_AGE_MS = 30 * 60 * 1000L
        private const val MAX_COMPLETION_RIDE_AGE_MS = 4 * 60 * 60 * 1000L

        // Avisos de expiração/cancelamento de OFERTA antes do aceite (não devem cancelar corrida anterior)
        private val NON_RIDE_CANCEL_MARKERS = listOf(
            "solicitação cancelada",
            "solicitacao cancelada",
            "cancelou a solicitação",
            "cancelou a solicitacao",
            "oferta cancelada",
            "oferta encerrada",
            "pedido de corrida cancelado",
            "pedido cancelado",
            "cancelou o pedido"
        )

        private val CANCEL_KEYWORDS = listOf(
            "viagem cancelada",
            "corrida cancelada",
            "viagem foi cancelada",
            "corrida foi cancelada",
            "cancelada pelo passageiro",
            "cancelado pelo passageiro",
            "cancelado pelo usuário",
            "cancelado pelo usuario",
            "cancelada pelo usuário",
            "cancelada pelo usuario",
            "passageiro cancelou",
            "usuário cancelou",
            "usuario cancelou",
            "cancelou a corrida",
            "cancelou a viagem",
            "corrida finalizada pelo passageiro",
            "taxa de cancelamento",
            "trip cancelled",
            "ride cancelled"
        )

        private val NO_FEE_KEYWORDS = listOf(
            "sem taxa",
            "sem cobrança",
            "sem cobranca",
            "nenhuma taxa",
            "sem custos",
            "taxa: r$ 0",
            "taxa de r$ 0",
            "r$ 0,00",
            "r$ 0.00",
            "r$ 0",
            "0,00",
            "0.00"
        )


        fun parseCancellationNotification(packageName: String, content: String): CancellationEvent? {
            val pkg = packageName.lowercase(Locale.ROOT)
            val lower = content.lowercase(Locale.ROOT)

            val isUberPkg = pkg.contains("ubercab") || (pkg.contains("test") && lower.contains("uber"))
            val is99Pkg = pkg.contains("app99") || pkg.contains("taxis99") || (pkg.contains("test") && lower.contains("99"))
            val isIfoodPkg = pkg.contains("ifood")

            // Proteção de isolamento: Aceita apenas pacotes oficiais de motorista
            if (!isUberPkg && !is99Pkg && !isIfoodPkg) {
                return null
            }

            val platform = when {
                isUberPkg -> "UBER"
                is99Pkg -> "99"
                isIfoodPkg -> "IFOOD"
                else -> return null
            }

            if (NON_RIDE_CANCEL_MARKERS.any { lower.contains(it) }) return null

            val hasCancelKeyword = CANCEL_KEYWORDS.any { lower.contains(it) }
            if (!hasCancelKeyword) return null

            // Verifica se o texto diz expressamente que não houve taxa
            val hasNoFee = NO_FEE_KEYWORDS.any { lower.contains(it) }
            if (hasNoFee) {
                return CancellationEvent(
                    platform = platform,
                    isCancelled = true,
                    fee = 0.0,
                    status = RideRecord.STATUS_CANCELADO,
                    reason = "Cancelada sem cobrança de taxa"
                )
            }

            // Só aceita taxa quando a notificação traz uma frase explícita de cobrança de cancelamento.
            // Nunca usa taxa padrão nem o primeiro R$ da notificação (saldo/antecipação viravam taxa).
            val extractedFee = ParsingEngine.extractExplicitCancellationFee(listOf(content))
            val finalFee = extractedFee ?: 0.0
            val status = if (finalFee > 0.0) RideRecord.STATUS_CANCELADO_TAXA else RideRecord.STATUS_CANCELADO

            return CancellationEvent(
                platform = platform,
                isCancelled = true,
                fee = finalFee,
                status = status,
                reason = "Cancelamento detectado via notificação"
            )
        }
    }
}
