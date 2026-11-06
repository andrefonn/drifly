package com.drifly.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class TripOdometerManager(
    private val context: Context,
    private val onDistanceUpdated: (Double, Double) -> Unit
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val prefs = PreferencesManager(context)

    private var lastLocation: Location? = null
    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
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
                        setPackage(context.packageName)
                        putExtra(EXTRA_SHIFT_KM, totalKm.toDouble())
                        putExtra(EXTRA_SHIFT_COST, shiftCost)
                    }
                    context.sendBroadcast(intent)
                }
            }
            lastLocation = location
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Permissão de localização não concedida para odômetro")
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(4f)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.i(TAG, "Odômetro de turno GPS ativado com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar rastreamento do odômetro", e)
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao desativar odômetro", e)
        }
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
        const val ACTION_ODOMETER_UPDATED = "com.drifly.app.ACTION_ODOMETER_UPDATED"
        const val EXTRA_SHIFT_KM = "EXTRA_SHIFT_KM"
        const val EXTRA_SHIFT_COST = "EXTRA_SHIFT_COST"
    }
}
