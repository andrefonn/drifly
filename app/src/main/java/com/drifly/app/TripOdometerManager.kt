package com.drifly.app

import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log

class TripOdometerManager(
    private val context: Context,
    private val onDistanceUpdated: (Double, Double) -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = PreferencesManager(appContext)

    private var lastLocation: Location? = null
    private var isTracking = false

    /**
     * O odômetro usa o MESMO fluxo de GPS do velocímetro da bolinha
     * ([GpsLocationStream]): uma única solicitação ativa no processo.
     */
    private val locationConsumer = GpsLocationStream.Consumer { location ->
        handleLocation(location)
    }

    private fun handleLocation(location: Location) {
        if (location.accuracy > 35f) {
            // Descarta leituras imprecisas com erro maior que 35 metros
            return
        }

        val prev = lastLocation
        if (prev != null) {
            val distanceMeters = prev.distanceTo(location)
            // Filtro contra ruído GPS parado (< 3 metros) e teletransporte / saltos anômalos (> 350 metros em 4s)
            if (distanceMeters in 3f..350f) {
                val addedKm = (distanceMeters / 1000.0).toFloat()
                val totalKm = prefs.shiftDistanceKm + addedKm
                prefs.shiftDistanceKm = totalKm
                val shiftCost = totalKm * prefs.costPerKm
                onDistanceUpdated(totalKm.toDouble(), shiftCost)

                val intent = Intent(ACTION_ODOMETER_UPDATED).apply {
                    setPackage(appContext.packageName)
                    putExtra(EXTRA_SHIFT_KM, totalKm.toDouble())
                    putExtra(EXTRA_SHIFT_COST, shiftCost)
                }
                appContext.sendBroadcast(intent)
            }
        }
        lastLocation = location
    }

    fun startTracking() {
        if (isTracking) return

        if (!GpsLocationStream.hasAnyLocationPermission(appContext)) {
            Log.w(TAG, "Permissão de localização não concedida para odômetro")
            return
        }

        isTracking = GpsLocationStream.subscribe(
            context = appContext,
            consumer = locationConsumer,
            intervalMs = UPDATE_INTERVAL_MS,
            minDistanceM = UPDATE_MIN_DISTANCE_M
        )
        if (isTracking) {
            Log.i(TAG, "Odômetro de turno GPS ativado com sucesso")
        } else {
            Log.e(TAG, "Erro ao iniciar rastreamento do odômetro")
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        GpsLocationStream.unsubscribe(locationConsumer)
        isTracking = false
        lastLocation = null
    }

    fun resetShift() {
        prefs.shiftDistanceKm = 0f
        prefs.shiftStartTime = System.currentTimeMillis()
        onDistanceUpdated(0.0, 0.0)
    }

    companion object {
        private const val TAG = "TripOdometerManager"
        private const val UPDATE_INTERVAL_MS = 4_000L
        private const val UPDATE_MIN_DISTANCE_M = 4f
        const val ACTION_ODOMETER_UPDATED = "com.drifly.app.ACTION_ODOMETER_UPDATED"
        const val EXTRA_SHIFT_KM = "EXTRA_SHIFT_KM"
        const val EXTRA_SHIFT_COST = "EXTRA_SHIFT_COST"
    }
}
