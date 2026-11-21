package com.drifly.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Geometria pura do velocímetro em relação à bolinha (testável na JVM).
 *
 * Regra: centralizado acima da bolinha; se não couber acima, desce para
 * baixo dela; nunca sai da área visível da tela.
 */
data class SpeedometerPosition(val x: Int, val y: Int)

object SpeedometerLayout {

    fun position(
        bubbleX: Int,
        bubbleY: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        readoutWidth: Int,
        readoutHeight: Int,
        gapPx: Int,
        screenWidth: Int,
        screenHeight: Int
    ): SpeedometerPosition {
        val centeredX = bubbleX + (bubbleWidth - readoutWidth) / 2f
        val aboveY = bubbleY - readoutHeight - gapPx
        val preferredY = if (aboveY >= 0) aboveY else bubbleY + bubbleHeight + gapPx

        val maxX = max(0, screenWidth - readoutWidth)
        val maxY = max(0, screenHeight - readoutHeight)
        return SpeedometerPosition(
            x = centeredX.roundToInt().coerceIn(0, maxX),
            y = preferredY.coerceIn(0, maxY)
        )
    }
}

/**
 * Janela complementar, não interativa, que exibe a velocidade acima da bolinha.
 *
 * A janela usa `TYPE_APPLICATION_OVERLAY`, não recebe foco nem toque
 * (`FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCHABLE`) e, no Android 12+, mantém
 * opacidade abaixo de 0,8 para que os toques continuem chegando aos apps
 * que estão atrás. É apenas um indicador: quem arrasta é a bolinha.
 */
class SpeedReadoutOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val view = SpeedReadoutView(context)

    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    val isAttached: Boolean
        get() = attached

    fun show() {
        if (attached) return

        // Mede antes de anexar para já posicionar sem "pulo" no primeiro frame.
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)

        val lp = overlayParams(view.measuredWidth, view.measuredHeight)
        try {
            windowManager.addView(view, lp)
            params = lp
            attached = true
        } catch (error: Exception) {
            AppLogger.e(
                "SpeedReadoutOverlay",
                "Falha ao adicionar indicador de velocidade: ${error.message}",
                error
            )
            params = null
            attached = false
        }
    }

    fun render(state: GpsSpeedState) {
        if (!attached) return
        // Atualiza apenas o texto: nada de recriar janela/View a cada leitura.
        view.valueText = state.displayValue
    }

    /** Esconde/mostra sem remover a janela (o leque aberto ocupa o espaço acima). */
    fun setVisible(visible: Boolean) {
        if (!attached) return
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun follow(
        bubbleX: Int,
        bubbleY: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (!attached) return
        val lp = params ?: return

        val readoutWidth = if (view.width > 0) view.width else view.measuredWidth
        val readoutHeight = if (view.height > 0) view.height else view.measuredHeight
        if (readoutWidth <= 0 || readoutHeight <= 0) return

        val position = SpeedometerLayout.position(
            bubbleX = bubbleX,
            bubbleY = bubbleY,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            readoutWidth = readoutWidth,
            readoutHeight = readoutHeight,
            gapPx = gapPx,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        if (lp.x == position.x && lp.y == position.y) return
        lp.x = position.x
        lp.y = position.y
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (error: Exception) {
            AppLogger.w(
                "SpeedReadoutOverlay",
                "Falha ao reposicionar indicador: ${error.message}"
            )
        }
    }

    fun hide() {
        if (!attached) return
        attached = false
        params = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private val gapPx: Int
        get() = (4f * context.resources.displayMetrics.density).roundToInt()

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ bloqueia toques através de overlays com opacidade > 0,8.
            // 0,79 garante a passagem dos toques sem perder legibilidade.
            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.79f else 1f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
