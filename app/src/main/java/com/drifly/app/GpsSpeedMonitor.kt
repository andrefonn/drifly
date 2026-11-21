package com.drifly.app

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Velocímetro GPS reativo da bolinha flutuante.
 *
 * - usa a velocidade medida pelo próprio GPS (`Location.getSpeed()`), em m/s,
 *   convertida para km/h;
 * - consulta a precisão da velocidade e a precisão horizontal para sinalizar
 *   baixa confiabilidade internamente;
 * - publica [GpsSpeedState] via [StateFlow]; a View só é invalidada quando o
 *   valor realmente muda;
 * - descarta a leitura após ~3 s sem atualização válida (`--`, nunca a última
 *   velocidade e nunca 0 inventado).
 */
class GpsSpeedMonitor(
    context: Context,
    private val staleAfterMs: Long = SpeedReadoutEngine.STALE_AFTER_MS
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(
        SpeedReadoutEngine.initial(GpsLocationStream.hasLocationPermission(appContext))
    )
    val state: StateFlow<GpsSpeedState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null
    private var subscribed = false
    private var lastFix: GpsFix? = null
    private var loggedLowConfidence = false
    private var loggedNoPermission = false

    private val consumer = GpsLocationStream.Consumer { location -> onLocation(location) }

    fun start() {
        if (subscribed) return

        val granted = GpsLocationStream.hasLocationPermission(appContext)
        _state.value = SpeedReadoutEngine.initial(granted)
        if (!granted) {
            logNoPermission()
            return
        }

        subscribed = GpsLocationStream.subscribe(
            context = appContext,
            consumer = consumer,
            intervalMs = UPDATE_INTERVAL_MS,
            minDistanceM = 0f
        )
        if (!subscribed) {
            AppLogger.w(TAG, "GPS recusou a assinatura do velocímetro")
            return
        }

        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = monitorScope
        ticker = monitorScope.launch {
            while (isActive) {
                delay(TICK_MS)
                publish()
            }
        }
        publish()
    }

    fun stop() {
        subscribed = false
        GpsLocationStream.unsubscribe(consumer)
        ticker?.cancel()
        ticker = null
        scope?.cancel()
        scope = null
        lastFix = null
        loggedLowConfidence = false
    }

    private fun onLocation(location: Location) {
        val hasSpeed = try {
            location.hasSpeed()
        } catch (_: Exception) {
            false
        }
        val speedAccuracy = try {
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
        } catch (_: Exception) {
            null
        }

        lastFix = GpsFix(
            timeMs = location.time,
            hasSpeed = hasSpeed,
            speedMps = if (hasSpeed) location.speed else 0f,
            speedAccuracyMps = speedAccuracy,
            horizontalAccuracyM = location.accuracy,
            provider = location.provider
        )
        publish()
    }

    private fun publish() {
        val granted = GpsLocationStream.hasLocationPermission(appContext)
        if (granted) {
            loggedNoPermission = false
            // Se a permissão foi concedida com o indicador já aberto, volta a assinar.
            if (!subscribed && scope != null) {
                subscribed = GpsLocationStream.subscribe(
                    context = appContext,
                    consumer = consumer,
                    intervalMs = UPDATE_INTERVAL_MS,
                    minDistanceM = 0f
                )
            }
        } else {
            logNoPermission()
        }

        val next = SpeedReadoutEngine.evaluate(
            fix = lastFix,
            nowMs = System.currentTimeMillis(),
            permissionGranted = granted,
            staleAfterMs = staleAfterMs
        )
        if (next != _state.value) {
            _state.value = next
        }

        if (next.lowConfidence != loggedLowConfidence) {
            loggedLowConfidence = next.lowConfidence
            if (next.lowConfidence) {
                AppLogger.w(
                    TAG,
                    "Leitura de velocidade com baixa confiabilidade: ${next.kmh} km/h"
                )
            }
        }
    }

    private fun logNoPermission() {
        if (loggedNoPermission) return
        loggedNoPermission = true
        AppLogger.w(TAG, "Permissão de localização precisa negada: velocímetro exibe --")
    }

    companion object {
        private const val TAG = "GpsSpeedMonitor"

        /** Intervalo desejado de ~1 s, sujeito ao que o provedor entregar. */
        const val UPDATE_INTERVAL_MS = 1_000L

        /** Reavalia o vencimento da leitura sem tocar no GPS. */
        private const val TICK_MS = 500L
    }
}
