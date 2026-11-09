package com.drifly.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class RideSimulationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == ACTION_SIMULATE_RIDE) {
            val prefs = PreferencesManager(context)

            val stopOverlay = intent.getBooleanExtra("stop_overlay", false)
            if (stopOverlay) {
                BubbleOverlayService.stop(context)
                return
            }

            val toggleMonitoring = intent.getBooleanExtra("toggle_monitoring", false)
            if (toggleMonitoring) {
                if (prefs.isMonitoringActive) {
                    MonitoringService.stop(context)
                } else {
                    MonitoringService.start(context)
                }
                return
            }

            // Permite validar o lembrete de apoio na hora, sem esperar o horário agendado
            if (intent.getBooleanExtra("test_support_notification", false)) {
                val posted = SupportNotificationWorker.postReminderIfAllowed(context)
                Log.i(TAG, "Teste do lembrete de apoio: publicado=$posted")
                return
            }

            // Permite inspecionar ou limpar logs de diagnóstico persistentes
            if (intent.getBooleanExtra("dump_diag_logs", false)) {
                AppLogger.init(context)
                val content = AppLogger.getLogContent()
                Log.i(TAG, "=== DRIFLY DIAG LOGS (${content.length} chars) ===\n$content")
                return
            }
            if (intent.getBooleanExtra("clear_diag_logs", false)) {
                AppLogger.init(context)
                AppLogger.clear()
                Log.i(TAG, "Logs de diagnóstico limpos com sucesso.")
                return
            }

            val setModoCasa = when {
                intent.hasExtra("set_modo_casa") -> intent.getBooleanExtra("set_modo_casa", false)
                intent.hasExtra("modo_casa") -> intent.getBooleanExtra("modo_casa", false)
                else -> null
            }
            if (setModoCasa != null) {
                prefs.isModoCasaActive = setModoCasa
                Log.i(TAG, "Modo Casa atualizado via ADB: $setModoCasa")
            }

            val setHomeAddress = intent.getStringExtra("set_home_address")
            if (!setHomeAddress.isNullOrBlank()) {
                prefs.homeAddress = setHomeAddress
                val coords = ModoCasaHelper.geocodeAddress(context, setHomeAddress)
                if (coords != null) {
                    prefs.homeLatitude = coords.first.toFloat()
                    prefs.homeLongitude = coords.second.toFloat()
                }
            }

            val addShiftKm = (intent.extras?.get("add_shift_km") as? Number)?.toDouble()
            if (addShiftKm != null && addShiftKm > 0) {
                val newTotal = prefs.shiftDistanceKm + addShiftKm.toFloat()
                prefs.shiftDistanceKm = newTotal
                val shiftCost = newTotal * prefs.costPerKm
                val odmIntent = Intent(TripOdometerManager.ACTION_ODOMETER_UPDATED).apply {
                    setPackage(context.packageName)
                    putExtra(TripOdometerManager.EXTRA_SHIFT_KM, newTotal.toDouble())
                    putExtra(TripOdometerManager.EXTRA_SHIFT_COST, shiftCost)
                }
                context.sendBroadcast(odmIntent)
                return
            }

            val platform = intent.getStringExtra("platform") ?: "99"
            val price = (intent.extras?.get("price") as? Number)?.toDouble() ?: 19.50
            val distance = (intent.extras?.get("distance") as? Number)?.toDouble() ?: 6.2
            val duration = (intent.extras?.get("duration") as? Number)?.toInt() ?: 15
            val rating = (intent.extras?.get("rating") as? Number)?.toFloat() ?: 4.88f
            val delaySeconds = (intent.extras?.get("delay_seconds") as? Number)?.toInt() ?: 0
            val destination = intent.getStringExtra("destination") ?: "Av. Paulista, 1578 - Bela Vista, São Paulo - SP"

            val homeDeltaKm = (intent.extras?.get("home_delta_km") as? Number)?.toDouble()
            val isHeadingHome = if (intent.hasExtra("is_heading_home")) intent.getBooleanExtra("is_heading_home", false) else (if (homeDeltaKm != null) homeDeltaKm <= 0.0 else null)
            val saveToDb = intent.getBooleanExtra("save_to_db", false)

            Log.i(TAG, "Solicitação de simulação recebida: $platform - R$$price ($distance km) delay=${delaySeconds}s dest=$destination deltaKm=$homeDeltaKm saveToDb=$saveToDb")

            val simulationRunnable = Runnable {
                RideSimulator.simulate(
                    context = context.applicationContext,
                    platform = platform,
                    price = price,
                    distanceKm = distance,
                    durationMinutes = duration,
                    rating = rating,
                    destinationAddress = destination,
                    isHeadingHome = isHeadingHome,
                    homeDistanceDeltaKm = homeDeltaKm,
                    saveToDb = saveToDb
                )
            }

            if (delaySeconds > 0) {
                Handler(Looper.getMainLooper()).postDelayed(simulationRunnable, delaySeconds * 1000L)
            } else {
                simulationRunnable.run()
            }
        }
    }

    companion object {
        private const val TAG = "RideSimulationReceiver"
        const val ACTION_SIMULATE_RIDE = "com.drifly.app.ACTION_SIMULATE_RIDE"
    }
}
