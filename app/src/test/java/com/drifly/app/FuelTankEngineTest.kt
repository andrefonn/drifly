package com.drifly.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regras do tanque virtual da bolinha: litros a partir da despesa,
 * consumo por km rodado e percentual exibido.
 */
class FuelTankEngineTest {

    @Test
    fun `vinte reais a 5 reais e 73 vira 3 litros e 49`() {
        val liters = FuelTankEngine.litersFromAmount(20.0, 5.73)
        assertEquals(3.49, liters, 0.01)
    }

    @Test
    fun `cem km com 31 km por litro consomem 3 litros e 22`() {
        val consumed = FuelTankEngine.consumedLiters(100.0, 31.0)
        assertEquals(3.226, consumed, 0.01)
    }

    @Test
    fun `abastecimento cheio comeca em cem por cento`() {
        val level = FuelTankEngine.levelOf(3.49, 3.49, 0.5f)
        assertEquals(1f, level, 0.0001f)
    }

    @Test
    fun `setenta km depois o tanque fica com pouco mais de dez por cento`() {
        val consumed = FuelTankEngine.consumedLiters(70.0, 31.0)
        val level = FuelTankEngine.levelOf(3.49 - consumed, 3.49, 0f)
        assertEquals(0.353f, level, 0.01f)
    }

    @Test
    fun `cem km depois cai para a faixa do radar`() {
        val consumed = FuelTankEngine.consumedLiters(100.0, 31.0)
        val level = FuelTankEngine.levelOf(3.49 - consumed, 3.49, 0f)
        assertEquals(0.076f, level, 0.01f)
    }

    @Test
    fun `tanque vazio nunca fica negativo`() {
        val consumed = FuelTankEngine.consumedLiters(400.0, 31.0)
        val level = FuelTankEngine.levelOf((3.49 - consumed).coerceAtLeast(0.0), 3.49, 0f)
        assertEquals(0f, level, 0.0001f)
    }

    @Test
    fun `sem abastecimento o nivel manual manda`() {
        assertEquals(0.62f, FuelTankEngine.levelOf(0.0, 0.0, 0.62f), 0.0001f)
    }

    @Test
    fun `preco do litro invalido nao gera litros infinitos`() {
        assertEquals(0.0, FuelTankEngine.litersFromAmount(50.0, 0.0), 0.0001)
    }
}
