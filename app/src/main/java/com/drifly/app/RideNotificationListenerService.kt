package com.drifly.app

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale
import java.util.regex.Pattern

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

        // 2. Se o monitoramento ativo estiver ligado, tenta extrair oferta de corrida
        if (prefs.isMonitoringActive) {
            val candidateTexts = mutableListOf<String>()
            if (title.isNotBlank()) candidateTexts.add(title)
            if (text.isNotBlank()) candidateTexts.add(text)
            if (bigText.isNotBlank()) candidateTexts.add(bigText)
            if (subText.isNotBlank()) candidateTexts.add(subText)
            if (ticker.isNotBlank()) candidateTexts.add(ticker)

            if (candidateTexts.isNotEmpty()) {
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

                        // Armazena como oferta pendente (SÓ salva no banco se o motorista ACEITAR a corrida)
                        RotaAccessibilityService.setPendingOffer(offer)

                        // Dispara flutuante na tela
                        BubbleOverlayService.showOffer(this, offer)
                    }
                }
            }
        }
    }

    private fun handleCancellationEvent(event: CancellationEvent) {
        // Busca a corrida estimada estritamente da mesma plataforma da notificação
        val latestEstimated = db.getLatestEstimatedRide(event.platform)

        if (latestEstimated != null) {
            db.updateRideStatus(latestEstimated.id, event.status, event.fee)

            val intent = Intent(ACTION_RIDE_CANCELLED_DETECTED).apply {
                putExtra("EXTRA_RIDE_ID", latestEstimated.id)
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

        private val CANCEL_KEYWORDS = listOf(
            "cancelad",
            "cancelou",
            "viagem cancelada",
            "corrida cancelada",
            "cancelado pelo passageiro",
            "cancelado pelo usuário",
            "corrida finalizada pelo passageiro",
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

        private val FEE_REGEX = Pattern.compile("r\\$\\s*([0-9]+(?:[\\.,][0-9]{3})*[\\.,][0-9]{2}|[0-9]+(?:[\\.,][0-9]{2})?)", Pattern.CASE_INSENSITIVE)

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

            // Tenta extrair taxa explícita da notificação (ex: "Taxa de cancelamento de R$ 5,50" ou "R$ 0,00")
            var extractedFee: Double? = null
            val matcher = FEE_REGEX.matcher(content)
            if (matcher.find()) {
                val feeStr = matcher.group(1)?.replace(".", "")?.replace(",", ".")
                val parsed = feeStr?.toDoubleOrNull()
                if (parsed != null) {
                    if (parsed == 0.0) {
                        return CancellationEvent(
                            platform = platform,
                            isCancelled = true,
                            fee = 0.0,
                            status = RideRecord.STATUS_CANCELADO,
                            reason = "Cancelada com taxa zero"
                        )
                    } else if (parsed > 0.0) {
                        extractedFee = parsed
                    }
                }
            }

            val standardFee = when (platform) {
                "99" -> 3.00
                "UBER" -> 5.50
                else -> 0.0
            }

            val finalFee = extractedFee ?: standardFee
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
