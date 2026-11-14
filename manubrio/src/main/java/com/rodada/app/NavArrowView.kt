package com.rodada.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Flecha grande de navegación, azul LED como el resto de la pantalla, según la maniobra. */
class NavArrowView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    enum class Turn { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN, ARRIVE }

    var turn = Turn.STRAIGHT
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 168, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 168, 255) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        stroke.strokeWidth = s * 0.13f
        val bottom = cy + s * 0.42f
        val head = s * 0.22f
        when (turn) {
            Turn.STRAIGHT -> {
                canvas.drawLine(cx, bottom, cx, cy - s * 0.2f, stroke)
                arrowHead(canvas, cx, cy - s * 0.42f, 0f, -1f, head)
            }
            Turn.LEFT, Turn.RIGHT -> {
                val d = if (turn == Turn.LEFT) -1f else 1f
                val x0 = cx - d * s * 0.18f
                val path = Path().apply {
                    moveTo(x0, bottom)
                    lineTo(x0, cy - s * 0.05f)
                    lineTo(x0 + d * s * 0.32f, cy - s * 0.05f)
                }
                canvas.drawPath(path, stroke)
                arrowHead(canvas, x0 + d * s * 0.52f, cy - s * 0.05f, d, 0f, head)
            }
            Turn.SLIGHT_LEFT, Turn.SLIGHT_RIGHT -> {
                val d = if (turn == Turn.SLIGHT_LEFT) -1f else 1f
                val path = Path().apply {
                    moveTo(cx - d * s * 0.1f, bottom)
                    lineTo(cx - d * s * 0.1f, cy + s * 0.08f)
                    lineTo(cx + d * s * 0.16f, cy - s * 0.18f)
                }
                canvas.drawPath(path, stroke)
                arrowHead(canvas, cx + d * s * 0.32f, cy - s * 0.34f, d * 0.707f, -0.707f, head)
            }
            Turn.UTURN -> {
                val r = s * 0.17f
                val xr = cx + r
                val xl = cx - r
                val top = cy - s * 0.18f
                val path = Path().apply {
                    moveTo(xr, bottom)
                    lineTo(xr, top)
                    arcTo(xl, top - r, xr, top + r, 0f, -180f, false)
                    lineTo(xl, cy + s * 0.12f)
                }
                canvas.drawPath(path, stroke)
                arrowHead(canvas, xl, cy + s * 0.34f, 0f, 1f, head)
            }
            Turn.ARRIVE -> {
                canvas.drawCircle(cx, cy - s * 0.1f, s * 0.22f, fill)
                canvas.drawLine(cx, cy + s * 0.1f, cx, bottom, stroke)
            }
        }
    }

    /** Triángulo con la punta en (x, y) mirando hacia (dx, dy). */
    private fun arrowHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float) {
        val bx = x - dx * size * 1.2f
        val by = y - dy * size * 1.2f
        val px = -dy * size
        val py = dx * size
        val path = Path().apply {
            moveTo(x, y)
            lineTo(bx + px, by + py)
            lineTo(bx - px, by - py)
            close()
        }
        canvas.drawPath(path, fill)
    }
}
