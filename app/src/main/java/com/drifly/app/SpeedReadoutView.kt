package com.drifly.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Texto do velocímetro com contorno real (Text Stroke), não sombra.
 *
 * O contorno é desenhado primeiro com [Paint.Style.STROKE] e o preenchimento
 * branco depois com [Paint.Style.FILL], ambos com antialiasing e junções
 * arredondadas. O fundo da View é totalmente transparente.
 */
class SpeedReadoutView(context: Context) : View(context) {

    var valueText: String = "--"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var unitText: String = "km/h"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val metrics = resources.displayMetrics
    private val density = metrics.density

    // SP respeita a escala de fonte do sistema; DP respeita a densidade da tela.
    private val numberSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, metrics)
    private val unitSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics)
    private val strokeWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, metrics)
    private val gapPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, metrics)

    private val minWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 76f, metrics)
    private val maxWidthPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 92f, metrics)
    private val fixedHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30f, metrics)

    private val numberTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val unitTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val numberStroke = textPaint(numberSizePx, numberTypeface).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }

    private val unitStroke = textPaint(unitSizePx, unitTypeface).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }

    private val numberFill = textPaint(numberSizePx, numberTypeface).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val unitFill = textPaint(unitSizePx, unitTypeface).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private fun textPaint(size: Float, typeface: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            this.typeface = typeface
            isDither = true
        }

    /** Largura estável (o indicador não muda de tamanho ao alternar entre números e `--`). */
    private fun stableWidthPx(): Int {
        val widestNumber = max(
            numberFill.measureText("188"),
            numberFill.measureText("--")
        )
        val unit = unitFill.measureText(unitText)
        val desired = ceil(widestNumber + gapPx + unit).toInt()
        return desired.coerceIn(minWidthPx.roundToInt(), maxWidthPx.roundToInt())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = stableWidthPx()
        val height = fixedHeightPx.roundToInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val number = valueText
        val unit = unitText
        val numberWidth = numberFill.measureText(number)
        val unitWidth = unitFill.measureText(unit)
        val totalWidth = numberWidth + gapPx + unitWidth

        val startX = (width - totalWidth) / 2f
        val metrics = numberFill.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f

        // 1) contorno preto das duas partes (borda externa)
        canvas.drawText(number, startX, baseline, numberStroke)
        canvas.drawText(unit, startX + numberWidth + gapPx, baseline, unitStroke)

        // 2) preenchimento branco por cima (miolo da letra)
        canvas.drawText(number, startX, baseline, numberFill)
        canvas.drawText(unit, startX + numberWidth + gapPx, baseline, unitFill)
    }
}
