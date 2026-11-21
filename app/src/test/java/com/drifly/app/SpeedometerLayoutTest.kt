package com.drifly.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometria do velocímetro: centralizado acima da bolinha, colado nela com
 * 4 dp, sem sair da tela e caindo para baixo quando não há espaço em cima.
 */
class SpeedometerLayoutTest {

    private val gap = 4
    private val screenW = 1080
    private val screenH = 1920
    private val bubble = 58
    private val readoutW = 80
    private val readoutH = 30

    @Test
    fun `fica centralizado acima da bolinha`() {
        val pos = SpeedometerLayout.position(
            bubbleX = 500, bubbleY = 900,
            bubbleWidth = bubble, bubbleHeight = bubble,
            readoutWidth = readoutW, readoutHeight = readoutH,
            gapPx = gap, screenWidth = screenW, screenHeight = screenH
        )
        assertEquals(500 + (bubble - readoutW) / 2, pos.x)
        assertEquals(900 - readoutH - gap, pos.y)
        // centros horizontais coincidem
        assertEquals(pos.x + readoutW / 2, 500 + bubble / 2)
    }

    @Test
    fun `sem espaco acima desce para baixo da bolinha`() {
        val pos = SpeedometerLayout.position(
            bubbleX = 500, bubbleY = 10,
            bubbleWidth = bubble, bubbleHeight = bubble,
            readoutWidth = readoutW, readoutHeight = readoutH,
            gapPx = gap, screenWidth = screenW, screenHeight = screenH
        )
        assertEquals(10 + bubble + gap, pos.y)
    }

    @Test
    fun `nao sai pela esquerda da tela`() {
        val pos = SpeedometerLayout.position(
            bubbleX = 0, bubbleY = 900,
            bubbleWidth = bubble, bubbleHeight = bubble,
            readoutWidth = readoutW, readoutHeight = readoutH,
            gapPx = gap, screenWidth = screenW, screenHeight = screenH
        )
        assertEquals(0, pos.x)
    }

    @Test
    fun `nao sai pela direita da tela`() {
        val pos = SpeedometerLayout.position(
            bubbleX = screenW - bubble, bubbleY = 900,
            bubbleWidth = bubble, bubbleHeight = bubble,
            readoutWidth = readoutW, readoutHeight = readoutH,
            gapPx = gap, screenWidth = screenW, screenHeight = screenH
        )
        assertEquals(screenW - readoutW, pos.x)
    }

    @Test
    fun `bolinha no rodape mantem o indicador inteiro visivel acima dela`() {
        val pos = SpeedometerLayout.position(
            bubbleX = 500, bubbleY = screenH - bubble,
            bubbleWidth = bubble, bubbleHeight = bubble,
            readoutWidth = readoutW, readoutHeight = readoutH,
            gapPx = gap, screenWidth = screenW, screenHeight = screenH
        )
        assertEquals(screenH - bubble - readoutH - gap, pos.y)
        assertTrue(pos.y + readoutH <= screenH)
    }
}
