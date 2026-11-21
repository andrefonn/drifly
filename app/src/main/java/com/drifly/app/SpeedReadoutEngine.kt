package com.drifly.app

import kotlin.math.roundToInt

/**
 * Leitura de GPS já normalizada, sem dependência do Android (testável na JVM).
 *
 * `hasSpeed` espelha [android.location.Location.hasSpeed]; `speedAccuracyMps`
 * espelha [android.location.Location.getSpeedAccuracyMetersPerSecond] e fica
 * nulo quando o provedor não informa a precisão.
 */
data class GpsFix(
    val timeMs: Long,
    val hasSpeed: Boolean,
    val speedMps: Float,
    val speedAccuracyMps: Float? = null,
    val horizontalAccuracyM: Float = 0f,
    val provider: String? = null
)

enum class GpsSpeedStatus {
    /** Velocidade válida e recente. */
    ACTIVE,

    /** GPS ligado, mas ainda sem leitura de velocidade. */
    SEARCHING,

    /** Última leitura tem mais de [SpeedReadoutEngine.STALE_AFTER_MS]. */
    STALE,

    /** Sem permissão de localização: o velocímetro não tem como medir. */
    PERMISSION_DENIED
}

data class GpsSpeedState(
    val status: GpsSpeedStatus,
    val kmh: Int?,
    val lowConfidence: Boolean = false,
    val ageMs: Long = Long.MAX_VALUE
) {
    /** O que aparece no indicador. Nunca reaproveita a última velocidade. */
    val displayValue: String
        get() = kmh?.toString() ?: "--"
}

/**
 * Regras puras de confiabilidade do velocímetro.
 *
 * O valor exibido vem SEMPRE de [android.location.Location.getSpeed] (GNSS),
 * convertido para km/h. Ausência de sinal/leitura vencida vira `--`, nunca 0.
 */
object SpeedReadoutEngine {

    const val MPS_TO_KMH = 3.6f

    /** Sem atualização válida por ~3 s a leitura é considerada vencida. */
    const val STALE_AFTER_MS = 3_000L

    /** Acima de ~7 km/h de erro na própria velocidade a leitura é pouco confiável. */
    const val LOW_SPEED_ACCURACY_MPS = 2.0f

    /** Precisão horizontal pior que 50 m indica leitura fraca (mas ainda exibível). */
    const val LOW_HORIZONTAL_ACCURACY_M = 50f

    /** Limite de sanidade contra leituras corrompidas (não é filtro de suavização). */
    const val MAX_PLAUSIBLE_KMH = 350

    fun evaluate(
        fix: GpsFix?,
        nowMs: Long,
        permissionGranted: Boolean,
        staleAfterMs: Long = STALE_AFTER_MS
    ): GpsSpeedState {
        if (!permissionGranted) {
            return GpsSpeedState(GpsSpeedStatus.PERMISSION_DENIED, null)
        }
        if (fix == null) {
            return GpsSpeedState(GpsSpeedStatus.SEARCHING, null)
        }

        val age = (nowMs - fix.timeMs).coerceAtLeast(0L)
        if (age > staleAfterMs) {
            return GpsSpeedState(GpsSpeedStatus.STALE, null, ageMs = age)
        }
        if (!fix.hasSpeed) {
            return GpsSpeedState(GpsSpeedStatus.SEARCHING, null, ageMs = age)
        }

        val mps = fix.speedMps
        if (!mps.isFinite() || mps < 0f) {
            return GpsSpeedState(GpsSpeedStatus.SEARCHING, null, ageMs = age)
        }

        val kmh = (mps * MPS_TO_KMH).roundToInt()
        if (kmh > MAX_PLAUSIBLE_KMH) {
            // Leitura claramente corrompida: não exibe número inventado.
            return GpsSpeedState(
                status = GpsSpeedStatus.SEARCHING,
                kmh = null,
                lowConfidence = true,
                ageMs = age
            )
        }

        val accuracy = fix.speedAccuracyMps
        val speedAccuracyPoor = accuracy != null && accuracy.isFinite() &&
            accuracy > LOW_SPEED_ACCURACY_MPS
        val horizontalPoor = fix.horizontalAccuracyM >= 0f &&
            fix.horizontalAccuracyM > LOW_HORIZONTAL_ACCURACY_M

        return GpsSpeedState(
            status = GpsSpeedStatus.ACTIVE,
            kmh = kmh,
            lowConfidence = speedAccuracyPoor || horizontalPoor,
            ageMs = age
        )
    }

    /** Estado inicial usado antes da primeira leitura e ao ligar o indicador. */
    fun initial(permissionGranted: Boolean): GpsSpeedState =
        if (permissionGranted) {
            GpsSpeedState(GpsSpeedStatus.SEARCHING, null)
        } else {
            GpsSpeedState(GpsSpeedStatus.PERMISSION_DENIED, null)
        }
}
