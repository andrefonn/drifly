package com.drifly.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fluxo único de localização do aplicativo.
 *
 * Tanto o odômetro do turno ([TripOdometerManager]) quanto o velocímetro da
 * bolinha ([GpsSpeedMonitor]) assinam este mesmo fluxo. Assim existe apenas
 * UMA solicitação de GPS ativa no processo, com o menor intervalo pedido
 * pelos assinantes (o velocímetro pede ~1 s, o odômetro aceita 4 s).
 *
 * As entregas acontecem na thread principal, como no restante do app.
 */
object GpsLocationStream {

    private const val TAG = "GpsLocationStream"
    private const val MIN_INTERVAL_MS = 250L

    fun interface Consumer {
        fun onLocation(location: Location)
    }

    private class Entry(
        val consumer: Consumer,
        val intervalMs: Long,
        val minDistanceM: Float
    )

    private val entries = mutableListOf<Entry>()

    private var fusedClient: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var activeIntervalMs = -1L
    private var activeMinDistanceM = -1f

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasAnyLocationPermission(context: Context): Boolean =
        hasLocationPermission(context) || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Assina o fluxo. Retorna `false` quando não há permissão de localização
     * ou quando o provedor recusa a solicitação.
     */
    @Synchronized
    fun subscribe(
        context: Context,
        consumer: Consumer,
        intervalMs: Long,
        minDistanceM: Float
    ): Boolean {
        val appContext = context.applicationContext
        entries.removeAll { it.consumer === consumer }
        entries.add(
            Entry(
                consumer = consumer,
                intervalMs = intervalMs.coerceAtLeast(MIN_INTERVAL_MS),
                minDistanceM = minDistanceM.coerceAtLeast(0f)
            )
        )

        if (!hasAnyLocationPermission(appContext)) {
            stopUpdatesLocked()
            return false
        }

        val interval = entries.minOf { it.intervalMs }
        val minDistance = entries.minOf { it.minDistanceM }
        if (callback != null && interval == activeIntervalMs && minDistance == activeMinDistanceM) {
            return true
        }

        stopUpdatesLocked()
        return startUpdatesLocked(appContext, interval, minDistance)
    }

    @Synchronized
    fun unsubscribe(consumer: Consumer) {
        entries.removeAll { it.consumer === consumer }
        if (entries.isEmpty()) stopUpdatesLocked()
    }

    @Synchronized
    fun isActive(): Boolean = callback != null

    @SuppressLint("MissingPermission")
    private fun startUpdatesLocked(
        context: Context,
        intervalMs: Long,
        minDistanceM: Float
    ): Boolean {
        val client = fusedClient
            ?: LocationServices.getFusedLocationProviderClient(context).also { fusedClient = it }

        val newCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                deliver(location)
            }
        }

        return try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(MIN_INTERVAL_MS))
                .setMinUpdateDistanceMeters(minDistanceM)
                .setWaitForAccurateLocation(false)
                .build()

            client.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    AppLogger.w(
                        TAG,
                        "Solicitação de GPS recusada: ${error.message}"
                    )
                }

            callback = newCallback
            activeIntervalMs = intervalMs
            activeMinDistanceM = minDistanceM
            AppLogger.d(
                TAG,
                "GPS compartilhado ativo: intervalo=${intervalMs}ms, distância mínima=${minDistanceM}m"
            )
            true
        } catch (error: Exception) {
            AppLogger.w(TAG, "Falha ao solicitar GPS: ${error.message}", error)
            false
        }
    }

    private fun stopUpdatesLocked() {
        val client = fusedClient
        val current = callback
        callback = null
        activeIntervalMs = -1L
        activeMinDistanceM = -1f
        if (client != null && current != null) {
            try {
                client.removeLocationUpdates(current)
            } catch (_: Exception) {
            }
        }
    }

    private fun deliver(location: Location) {
        val targets = synchronized(this) { entries.map { it.consumer } }
        targets.forEach { consumer ->
            try {
                consumer.onLocation(location)
            } catch (error: Exception) {
                AppLogger.w(TAG, "Assinante de GPS falhou: ${error.message}", error)
            }
        }
    }
}
