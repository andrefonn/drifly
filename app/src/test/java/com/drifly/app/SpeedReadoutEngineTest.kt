package com.drifly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras do velocímetro GPS: conversão m/s -> km/h, estados de sinal,
 * vencimento da leitura, zero real e confiabilidade.
 */
class SpeedReadoutEngineTest {

    private fun fix(
        timeMs: Long = 1_000L,
        hasSpeed: Boolean = true,
        speedMps: Float = 18.611f,
        speedAccuracyMps: Float? = 0.4f,
        horizontalAccuracyM: Float = 5f
    ) = GpsFix(
        timeMs = timeMs,
        hasSpeed = hasSpeed,
        speedMps = speedMps,
        speedAccuracyMps = speedAccuracyMps,
        horizontalAccuracyM = horizontalAccuracyM
    )

    @Test
    fun `18 metros e 61 por segundo viram 67 km por hora`() {
        val state = SpeedReadoutEngine.evaluate(fix(), nowMs = 1_500L, permissionGranted = true)
        assertEquals(GpsSpeedStatus.ACTIVE, state.status)
        assertEquals(67, state.kmh)
        assertEquals("67", state.displayValue)
    }

    @Test
    fun `sem permissao nao inventa leitura e mostra dois tracos`() {
        val state = SpeedReadoutEngine.evaluate(fix(), nowMs = 1_500L, permissionGranted = false)
        assertEquals(GpsSpeedStatus.PERMISSION_DENIED, state.status)
        assertNull(state.kmh)
        assertEquals("--", state.displayValue)
    }

    @Test
    fun `gps buscando sinal mostra dois tracos`() {
        val state = SpeedReadoutEngine.evaluate(null, nowMs = 1_500L, permissionGranted = true)
        assertEquals(GpsSpeedStatus.SEARCHING, state.status)
        assertEquals("--", state.displayValue)
    }

    @Test
    fun `posicao sem velocidade informada mostra dois tracos`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(hasSpeed = false, speedMps = 0f),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.SEARCHING, state.status)
        assertEquals("--", state.displayValue)
    }

    @Test
    fun `leitura com mais de tres segundos vence e nao repete a ultima velocidade`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(timeMs = 1_000L),
            nowMs = 4_001L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.STALE, state.status)
        assertNull(state.kmh)
        assertEquals("--", state.displayValue)
    }

    @Test
    fun `leitura no limite de tres segundos ainda vale`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(timeMs = 1_000L),
            nowMs = 4_000L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.ACTIVE, state.status)
        assertEquals(67, state.kmh)
    }

    @Test
    fun `velocidade zero real e exibida como zero`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(speedMps = 0f),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.ACTIVE, state.status)
        assertEquals(0, state.kmh)
        assertEquals("0", state.displayValue)
        assertFalse(state.lowConfidence)
    }

    @Test
    fun `velocidade negativa ou invalida nao vira numero`() {
        val negative = SpeedReadoutEngine.evaluate(
            fix(speedMps = -3f),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.SEARCHING, negative.status)
        assertNull(negative.kmh)

        val nan = SpeedReadoutEngine.evaluate(
            fix(speedMps = Float.NaN),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.SEARCHING, nan.status)
        assertNull(nan.kmh)
    }

    @Test
    fun `precisao ruim da velocidade marca baixa confiabilidade sem esconder o valor`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(speedMps = 10f, speedAccuracyMps = 4f, horizontalAccuracyM = 8f),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.ACTIVE, state.status)
        assertEquals(36, state.kmh)
        assertTrue(state.lowConfidence)
    }

    @Test
    fun `precisao horizontal ruim tambem marca baixa confiabilidade`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(speedAccuracyMps = 0.5f, horizontalAccuracyM = 80f),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(GpsSpeedStatus.ACTIVE, state.status)
        assertTrue(state.lowConfidence)
    }

    @Test
    fun `leitura absurda e descartada em vez de exibida`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(speedMps = 150f), // 540 km/h
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertNull(state.kmh)
        assertTrue(state.lowConfidence)
    }

    @Test
    fun `arredondamento usa a leitura do gps sem suavizar`() {
        val state = SpeedReadoutEngine.evaluate(
            fix(speedMps = 10.5f),
            nowMs = 1_500L,
            permissionGranted = true
        )
        assertEquals(38, state.kmh)
    }
}
