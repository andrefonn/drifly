package com.drifly.app

/**
 * Utilitário matemático e de geometria de toques do Overlay Flutuante.
 * Implementa o cálculo do raio circular de exclusão (~52 dp ao redor do centro do botão X),
 * detecção de arraste (drag) vs toque estático (tap) e proteção contra duplo toque (debounce).
 */
object OverlayTouchMath {

    /** Raio da zona de exclusão circular do botão fechar (X) em dp. */
    const val CLOSE_EXCLUSION_RADIUS_DP: Float = 52.0f

    /** Deslocamento mínimo para caracterizar arrasto da janela flutuante em dp. */
    const val DEFAULT_TOUCH_SLOP_DP: Float = 10.0f

    /** Janela de debouncing contra duplo toque no aceite em milissegundos. */
    const val ACCEPT_DEBOUNCE_MS: Long = 3000L

    /**
     * Calcula a distância Euclidiana entre as coordenadas de toque (rawX, rawY) e o centro
     * do botão de fechar (centerX, centerY).
     *
     * Fórmula: d = sqrt((rawX - centerX)^2 + (rawY - centerY)^2)
     */
    fun calculateDistanceToCenter(rawX: Float, rawY: Float, centerX: Float, centerY: Float): Float {
        val dx = (rawX - centerX).toDouble()
        val dy = (rawY - centerY).toDouble()
        return Math.hypot(dx, dy).toFloat()
    }

    /**
     * Determina se a distância calculada está dentro do raio circular de proteção do botão X.
     * Toques dentro desse raio apenas fecham a bolha e NUNCA disparam aceite.
     */
    fun isWithinCloseExclusionZone(
        distancePx: Float,
        density: Float,
        exclusionRadiusDp: Float = CLOSE_EXCLUSION_RADIUS_DP
    ): Boolean {
        val radiusPx = exclusionRadiusDp * density
        return distancePx <= radiusPx
    }

    /**
     * Avalia diretamente se as coordenadas (rawX, rawY) recaem dentro da zona de exclusão
     * circular a partir do centro (centerX, centerY).
     */
    fun isTouchInExclusionZone(
        rawX: Float,
        rawY: Float,
        centerX: Float,
        centerY: Float,
        density: Float,
        exclusionRadiusDp: Float = CLOSE_EXCLUSION_RADIUS_DP
    ): Boolean {
        val dist = calculateDistanceToCenter(rawX, rawY, centerX, centerY)
        return isWithinCloseExclusionZone(dist, density, exclusionRadiusDp)
    }

    /**
     * Determina se a variação linear do toque supera a folga mínima (touch slop),
     * configurando um gesto de arrastar/reposicionar a janela e não um clique.
     */
    fun isDrag(
        startX: Float,
        startY: Float,
        currentX: Float,
        currentY: Float,
        density: Float,
        touchSlopDp: Float = DEFAULT_TOUCH_SLOP_DP
    ): Boolean {
        val dx = Math.abs(currentX - startX)
        val dy = Math.abs(currentY - startY)
        val slopPx = touchSlopDp * density
        return dx > slopPx || dy > slopPx
    }

    /**
     * Avalia a proteção contra duplo toque.
     * Retorna true se o toque deve ser processado, ou false se for bloqueado por disparo prévio
     * ou debounce.
     */
    fun shouldAllowAcceptTap(
        isTriggered: Boolean,
        lastTapTimestamp: Long,
        currentTimestamp: Long,
        debounceMs: Long = ACCEPT_DEBOUNCE_MS
    ): Boolean {
        if (isTriggered) return false
        if ((currentTimestamp - lastTapTimestamp) < debounceMs) return false
        return true
    }

    /**
     * Verifica se as coordenadas globais de toque (rawX, rawY) caem dentro do retângulo delimitador
     * de uma view na tela.
     */
    fun isInsideBounds(rawX: Float, rawY: Float, left: Int, top: Int, width: Int, height: Int): Boolean {
        return rawX >= left && rawX <= (left + width) && rawY >= top && rawY <= (top + height)
    }
}
