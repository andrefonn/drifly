package com.drifly.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.*

data class HomeDirectionResult(
    val isHeadingHome: Boolean,
    val deltaKm: Double,
    val verdictText: String,
    val isBeneficial: Boolean
)

object ModoCasaHelper {
    private const val TAG = "ModoCasaHelper"
    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Calcula a distância em KM entre dois pontos geográficos usando a fórmula de Haversine.
     * Implementação pura em Kotlin para permitir execução em testes unitários sem mock de framework.
     */
    fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Calcula a variação de distância até a casa do motorista:
     * deltaKm = distDestToHome - distDriverToHome
     * Se deltaKm < 0: aproxima de casa (reduz distância)
     * Se deltaKm > 0: afasta de casa (aumenta distância)
     */
    fun calculateDeltaKm(
        driverLat: Double,
        driverLng: Double,
        destLat: Double,
        destLng: Double,
        homeLat: Double,
        homeLng: Double
    ): Double {
        val distDriverToHome = calculateDistanceKm(driverLat, driverLng, homeLat, homeLng)
        val distDestToHome = calculateDistanceKm(destLat, destLng, homeLat, homeLng)
        val delta = distDestToHome - distDriverToHome
        return Math.round(delta * 10.0) / 10.0
    }

    /**
     * Retorna o veredito direcional correspondente à variação em KM:
     * Aproximando (delta <= 0):
     *   delta <= -8.0 -> "Perfeita Direção ✅"
     *   -8.0 < delta <= -4.0 -> "Excelente Direção ✅"
     *   -4.0 < delta <= 0.0 -> "Boa Direção ✅"
     * Afastando (delta > 0):
     *   0.0 < delta <= 4.0 -> "Ruim Direção ❌"
     *   4.0 < delta <= 9.0 -> "Péssima Direção ❌"
     *   delta > 9.0 -> "Evitar Direção ❌"
     */
    fun getVerdictForDelta(deltaKm: Double): String {
        return if (deltaKm <= 0.0) {
            when {
                deltaKm <= -8.0 -> "Perfeita Direção ✅"
                deltaKm <= -4.0 -> "Excelente Direção ✅"
                else -> "Boa Direção ✅"
            }
        } else {
            when {
                deltaKm <= 4.0 -> "Ruim Direção ❌"
                deltaKm <= 9.0 -> "Péssima Direção ❌"
                else -> "Evitar Direção ❌"
            }
        }
    }

    /**
     * Geocodifica um endereço textual em coordenadas Lat/Lng.
     */
    fun geocodeAddress(context: Context, address: String): Pair<Double, Double>? {
        if (address.isBlank()) return null
        return try {
            val geocoder = Geocoder(context, Locale("pt", "BR"))
            val results = geocoder.getFromLocationName(address, 1)
            if (!results.isNullOrEmpty()) {
                val loc = results[0]
                Pair(loc.latitude, loc.longitude)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Falha na geocodificação de '$address': ${e.message}")
            null
        }
    }

    /**
     * Obtém a última localização GPS conhecida do dispositivo com segurança de permissões.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var bestLocation: Location? = null
        try {
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val passiveLoc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            for (loc in listOfNotNull(gpsLoc, netLoc, passiveLoc)) {
                if (bestLocation == null || loc.time > bestLocation.time) {
                    bestLocation = loc
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao obter localização: ${e.message}")
        }
        return bestLocation
    }

    /**
     * Avalia uma oferta de corrida em direção à residência configurada pelo motorista.
     */
    fun evaluateRideTowardsHome(
        context: Context,
        destinationAddress: String?,
        currentLoc: Location? = null
    ): HomeDirectionResult {
        val prefs = PreferencesManager(context)
        if (!prefs.isModoCasaActive) {
            return HomeDirectionResult(
                isHeadingHome = false,
                deltaKm = 0.0,
                verdictText = "",
                isBeneficial = false
            )
        }

        val homeLat = prefs.homeLatitude.toDouble()
        val homeLng = prefs.homeLongitude.toDouble()
        if (homeLat == 0.0 && homeLng == 0.0) {
            return HomeDirectionResult(
                isHeadingHome = false,
                deltaKm = 0.0,
                verdictText = "",
                isBeneficial = false
            )
        }

        val driverLocation = currentLoc ?: getLastKnownLocation(context)
        if (driverLocation == null) {
            return HomeDirectionResult(
                isHeadingHome = false,
                deltaKm = 0.0,
                verdictText = "Modo Casa Ativo",
                isBeneficial = true
            )
        }

        if (destinationAddress.isNullOrBlank()) {
            return HomeDirectionResult(
                isHeadingHome = false,
                deltaKm = 0.0,
                verdictText = "Modo Casa Ativo",
                isBeneficial = true
            )
        }

        val destCoords = geocodeAddress(context, destinationAddress)
        if (destCoords == null) {
            return HomeDirectionResult(
                isHeadingHome = false,
                deltaKm = 0.0,
                verdictText = "Modo Casa Ativo",
                isBeneficial = true
            )
        }

        val deltaKm = calculateDeltaKm(
            driverLat = driverLocation.latitude,
            driverLng = driverLocation.longitude,
            destLat = destCoords.first,
            destLng = destCoords.second,
            homeLat = homeLat,
            homeLng = homeLng
        )

        val isBeneficial = deltaKm <= 0.0
        val verdict = getVerdictForDelta(deltaKm)

        return HomeDirectionResult(
            isHeadingHome = isBeneficial,
            deltaKm = deltaKm,
            verdictText = verdict,
            isBeneficial = isBeneficial
        )
    }
}
