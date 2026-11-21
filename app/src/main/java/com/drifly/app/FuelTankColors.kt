package com.drifly.app

/**
 * Faixas de cor da bolinha do tanque.
 *
 * 100–60% verde, 60→45% amarelando, 45–30% amarelo puro,
 * 30→10% avermelhando, ≤10% vermelho puro.
 */
object FuelTankColors {

    const val GREEN = 0xFF0B8A54.toInt()
    const val AMBER = 0xFFFFAA00.toInt()
    const val RED = 0xFFD62839.toInt()

    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun colorForLevel(level: Float): Int {
        val l = level.coerceIn(0f, 1f)
        return when {
            l >= 0.60f -> GREEN
            l >= 0.45f -> blend(GREEN, AMBER, (0.60f - l) / 0.15f)
            l >= 0.30f -> AMBER
            l >= 0.10f -> blend(AMBER, RED, (0.30f - l) / 0.20f)
            else -> RED
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return rgb(
            (red(from) + (red(to) - red(from)) * tt).toInt(),
            (green(from) + (green(to) - green(from)) * tt).toInt(),
            (blue(from) + (blue(to) - blue(from)) * tt).toInt()
        )
    }
}
