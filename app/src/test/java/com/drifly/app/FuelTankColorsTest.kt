package com.drifly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faixas de cor da bolinha: verde → amarelo → vermelho. */
class FuelTankColorsTest {

    @Test
    fun `acima de sessenta por cento e verde`() {
        assertEquals(FuelTankColors.GREEN, FuelTankColors.colorForLevel(1f))
        assertEquals(FuelTankColors.GREEN, FuelTankColors.colorForLevel(0.60f))
    }

    @Test
    fun `entre sessenta e quarenta e cinco esta amarelando`() {
        val meio = FuelTankColors.colorForLevel(0.525f)
        assertTrue(FuelTankColors.red(meio) > FuelTankColors.red(FuelTankColors.GREEN))
        assertTrue(FuelTankColors.red(meio) < FuelTankColors.red(FuelTankColors.AMBER))
        // amarelo tem mais verde que o verde da faixa cheia: o meio fica entre os dois
        assertTrue(FuelTankColors.green(meio) in (FuelTankColors.green(FuelTankColors.GREEN) + 1) until FuelTankColors.green(FuelTankColors.AMBER))
    }

    @Test
    fun `entre quarenta e cinco e trinta e amarelo puro`() {
        assertEquals(FuelTankColors.AMBER, FuelTankColors.colorForLevel(0.45f))
        assertEquals(FuelTankColors.AMBER, FuelTankColors.colorForLevel(0.38f))
        assertEquals(FuelTankColors.AMBER, FuelTankColors.colorForLevel(0.30f))
    }

    @Test
    fun `entre trinta e dez esta avermelhando`() {
        val meio = FuelTankColors.colorForLevel(0.20f)
        // vai perdendo verde e escurecendo em direção ao vermelho puro
        assertTrue(FuelTankColors.green(meio) < FuelTankColors.green(FuelTankColors.AMBER))
        assertTrue(FuelTankColors.red(meio) < FuelTankColors.red(FuelTankColors.AMBER))
        assertTrue(FuelTankColors.red(meio) > FuelTankColors.red(FuelTankColors.RED))
    }

    @Test
    fun `dez por cento ou menos e vermelho puro`() {
        assertEquals(FuelTankColors.RED, FuelTankColors.colorForLevel(0.10f))
        assertEquals(FuelTankColors.RED, FuelTankColors.colorForLevel(0.07f))
        assertEquals(FuelTankColors.RED, FuelTankColors.colorForLevel(0f))
    }

    @Test
    fun `nivel fora da faixa nao quebra`() {
        assertEquals(FuelTankColors.GREEN, FuelTankColors.colorForLevel(2f))
        assertEquals(FuelTankColors.RED, FuelTankColors.colorForLevel(-1f))
    }
}
