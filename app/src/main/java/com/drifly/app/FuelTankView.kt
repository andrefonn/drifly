package com.drifly.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Bolinha flutuante do tanque.
 *
 * - líquido que ondula conforme o movimento (sensores) e esvazia suavemente;
 * - cor por faixa: verde (100–60%), amarelo (45–30%), vermelho (≤10%);
 * - ≤7% dispara ondas de radar vermelhas lentas;
 * - com o menu aberto o líquido some e a bolinha vira um X vermelho.
 */
class FuelTankView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var level: Float = 0.62f
        set(value) {
            field = value.coerceIn(0f, 1f)
            targetLevel = field
            invalidate()
        }

    /** Menu aberto: mostra o X de fechar no lugar do combustível. */
    var menuMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var targetLevel = 0.62f
    private var shownLevel = 0.62f

    private var tilt = 0f
    private var phase = 0f
    private var velocity = 0f
    private var radarPhase = 0f
    private var running = false

    private val dens = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dens
        color = 0xFFE7ECF2.toInt()
    }
    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dens
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * dens
        strokeCap = Paint.Cap.ROUND
        color = FuelTankColors.RED
    }
    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(46, 214, 40, 57)
    }
    private val closeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * dens
        color = Color.argb(170, 214, 40, 57)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textSize = 12.5f * dens
        color = Color.WHITE
        setShadowLayer(2.5f * dens, 0f, 1f * dens, 0x66000000)
    }

    private val clipPath = Path()
    private val liquidPath = Path()
    private val circleRect = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            phase += 0.075f
            velocity *= 0.90f
            if (abs(tilt) < 0.001f) tilt *= 0.9f

            // esvaziamento/enchimento suave (o líquido desce, não pula)
            val diff = targetLevel - shownLevel
            if (abs(diff) > 0.0015f) {
                shownLevel += diff * 0.12f
            } else {
                shownLevel = targetLevel
            }

            if (shownLevel <= 0.075f) radarPhase = (radarPhase + 0.012f) % 1f else radarPhase = 0f
            invalidate()
            Handler(Looper.getMainLooper()).postDelayed(this, 33L)
        }
    }

    fun setTilt(value: Float) {
        tilt = value.coerceIn(-1f, 1f)
        velocity = max(velocity, abs(value) * 0.6f)
    }

    fun nudge(strength: Float) {
        velocity = (velocity + abs(strength)).coerceAtMost(1.6f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicker()
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        super.onDetachedFromWindow()
    }

    private fun startTicker() {
        if (running) return
        running = true
        Handler(Looper.getMainLooper()).post(ticker)
    }

    private fun stopTicker() {
        running = false
        Handler(Looper.getMainLooper()).removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = minOf(w, h) / 2f - 2f * dens
        val cx = w / 2f
        val cy = h / 2f
        val color = FuelTankColors.colorForLevel(shownLevel)
        circleRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        if (menuMode) {
            drawCloseState(canvas, cx, cy, radius)
        } else {
            drawLiquidState(canvas, cx, cy, radius, color)
            val overLiquid = shownLevel > 0.66f
            textPaint.color = if (overLiquid) Color.WHITE else 0xFF162033.toInt()
            textPaint.setShadowLayer(
                if (overLiquid) 2.5f * dens else 0f,
                0f,
                if (overLiquid) 1f * dens else 0f,
                if (overLiquid) 0x66000000 else Color.TRANSPARENT
            )
            canvas.drawText("${(shownLevel * 100).toInt()}%", cx, cy + textPaint.textSize / 3f, textPaint)
            if (shownLevel <= 0.075f) drawRadar(canvas, cx, cy, radius)
        }

        canvas.drawCircle(cx, cy, radius, ringPaint)
    }

    private fun drawLiquidState(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        // fundo: tom claro da cor da faixa (tanque vazio ainda lê a cor)
        bgPaint.color = Color.rgb(
            255 - ((255 - Color.red(color)) * 0.16f).toInt(),
            255 - ((255 - Color.green(color)) * 0.16f).toInt(),
            255 - ((255 - Color.blue(color)) * 0.16f).toInt()
        )
        canvas.drawCircle(cx, cy, radius, bgPaint)

        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val surface = cy + radius - (2f * radius * shownLevel)
        val amplitude = (1.5f + 5.5f * max(abs(tilt), velocity)).coerceAtMost(7f) * dens
        val phaseShift = tilt * 0.9f

        liquidPath.reset()
        liquidPath.moveTo(cx - radius - 2f, cy + radius + 2f)
        liquidPath.lineTo(cx - radius - 2f, surface)
        var x = cx - radius
        while (x <= cx + radius + 2f) {
            val progress = (x - (cx - radius)) / (2f * radius)
            val y = surface +
                sin(progress * 2f * PI.toFloat() + phase) * amplitude +
                sin(progress * 1f * PI.toFloat() + phase * 0.6f).toFloat() * amplitude * 0.35f +
                phaseShift * radius * 0.18f
            liquidPath.lineTo(x, y)
            x += 2f * dens
        }
        liquidPath.lineTo(cx + radius + 2f, cy + radius + 2f)
        liquidPath.close()

        val lighter = Color.argb(235, Color.red(color), Color.green(color), Color.blue(color))
        liquidPaint.shader = LinearGradient(
            0f, surface, 0f, cy + radius,
            lighter, color, Shader.TileMode.CLAMP
        )
        canvas.drawPath(liquidPath, liquidPaint)
        liquidPaint.shader = null

        // brilho de vidro no topo
        liquidPaint.shader = RadialGradient(
            cx - radius * 0.35f, cy - radius * 0.5f, radius * 1.35f,
            Color.argb(80, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, liquidPaint)
        liquidPaint.shader = null

        canvas.restore()
    }

    /** Ondas de radar vermelhas saindo da borda (tanque quase vazio). */
    private fun drawRadar(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val red = FuelTankColors.RED
        for (i in 0..2) {
            val progress = (radarPhase + i / 3f) % 1f
            val waveRadius = radius * (1.02f + progress * 0.55f)
            val alpha = ((1f - progress) * 130).toInt().coerceIn(0, 255)
            radarPaint.color = Color.argb(alpha, Color.red(red), Color.green(red), Color.blue(red))
            radarPaint.strokeWidth = (2.4f - progress * 1.4f) * dens
            canvas.drawCircle(cx, cy, waveRadius, radarPaint)
        }
    }

    /** Menu aberto: fundo claro e X vermelho grosso. */
    private fun drawCloseState(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, bgPaint.apply { color = Color.WHITE })
        canvas.drawCircle(cx, cy, radius, closeBgPaint)
        canvas.drawCircle(cx, cy, radius - 3f * dens, closeRingPaint)

        val arm = radius * 0.32f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, closePaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, closePaint)
    }
}
