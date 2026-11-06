package com.drifly.app

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object RideSimulator {
    private const val TAG = "RideSimulator"

    fun simulate(
        context: Context,
        platform: String = "99",
        price: Double = 19.50,
        distanceKm: Double = 6.2,
        durationMinutes: Int = 15,
        rating: Float = 4.88f,
        destinationAddress: String? = "Av. Paulista, 1578 - Bela Vista, São Paulo - SP",
        isHeadingHome: Boolean? = null,
        homeDistanceDeltaKm: Double? = null,
        saveToDb: Boolean = false
    ): RideOffer {
        val prefs = PreferencesManager(context)
        val db = DatabaseHelper(context)

        val costPerKm = prefs.costPerKm
        val ratePerKm = if (distanceKm > 0) Math.round((price / distanceKm) * 100.0) / 100.0 else 0.0
        val ratePerHour = if (durationMinutes > 0) Math.round((price / (durationMinutes / 60.0)) * 10.0) / 10.0 else 0.0
        val fuelCost = Math.round((distanceKm * costPerKm) * 100.0) / 100.0
        val netProfit = Math.round((price - fuelCost) * 100.0) / 100.0
        val isLow = rating < prefs.minPassengerRating

        val effectiveHeadingHome: Boolean
        val effectiveDeltaKm: Double
        if (isHeadingHome != null && homeDistanceDeltaKm != null) {
            effectiveHeadingHome = isHeadingHome
            effectiveDeltaKm = homeDistanceDeltaKm
        } else if (prefs.isModoCasaActive) {
            val eval = ModoCasaHelper.evaluateRideTowardsHome(context, destinationAddress)
            effectiveHeadingHome = eval.isHeadingHome
            effectiveDeltaKm = eval.deltaKm
        } else {
            effectiveHeadingHome = false
            effectiveDeltaKm = 0.0
        }

        val offer = RideOffer(
            platform = platform,
            price = price,
            distanceKm = distanceKm,
            durationMinutes = durationMinutes,
            ratePerKm = ratePerKm,
            ratePerHour = ratePerHour,
            estimatedFuelCost = fuelCost,
            netProfit = netProfit,
            passengerRating = rating,
            isLowRating = isLow,
            destinationAddress = destinationAddress,
            isHeadingHome = effectiveHeadingHome,
            homeDistanceDeltaKm = effectiveDeltaKm
        )

        Log.i(TAG, "Simulando oferta [$platform]: R$ $price | $distanceKm km | Lucro R$ $netProfit | ModoCasa: $effectiveHeadingHome ($effectiveDeltaKm km) | saveToDb=$saveToDb")

        // 1. Apenas registra no banco financeiro se solicitado explicitamente (não polui o extrato em simulações visuais de overlay)
        if (saveToDb) {
            db.insertRide(
                platform = platform,
                gross = price,
                distance = distanceKm,
                net = netProfit,
                status = RideRecord.STATUS_ESTIMADO,
                originalPrice = price
            )
            context.sendBroadcast(Intent(RotaAccessibilityService.ACTION_RIDE_UPDATED).apply {
                setPackage(context.packageName)
            })
        } else {
            RotaAccessibilityService.setPendingOffer(offer)
        }

        // 2. Dispara a bolha/overlay diretamente na tela
        BubbleOverlayService.showOffer(context, offer)

        // 3. Som e vibração para reproduzir a sensação real de toque de corrida
        playAlertSoundAndVibrate(context)

        return offer
    }

    private fun playAlertSoundAndVibrate(context: Context) {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao tocar som de teste", e)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(350)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao vibrar no teste", e)
        }
    }
}
