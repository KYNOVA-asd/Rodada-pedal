package com.rodada.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Cinco destinos grandes; cada toque se asigna al centro más cercano. */
class MusicWheelView(context: Context, attrs: android.util.AttributeSet?) : View(context, attrs) {
    enum class Zone { UP, DOWN, PREVIOUS, NEXT, CENTER }

    var onControl: ((Zone) -> Unit)? = null
    private var pressedZone: Zone? = null
    private var clickZone: Zone? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 48, 48) }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 70, 70) }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 100, 100)
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 100, 100) }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = wheelRadius()
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, wheelPaint)
        for (horizontal in listOf(-1f, 1f)) {
            for (vertical in listOf(-1f, 1f)) {
                canvas.drawLine(
                    cx + horizontal * radius * 0.24f,
                    cy + vertical * radius * 0.24f,
                    cx + horizontal * radius * 0.67f,
                    cy + vertical * radius * 0.67f,
                    dividerPaint
                )
            }
        }
        canvas.drawCircle(cx, cy, radius * 0.32f, centerPaint)

        pressedZone?.let { zone ->
            val (x, y) = zoneCenter(zone, cx, cy, radius)
            canvas.drawCircle(x, y, radius * 0.27f, pressedPaint)
        }

        symbolPaint.strokeWidth = radius * 0.045f
        val arm = radius * 0.17f
        val topY = cy - radius * 0.64f
        val bottomY = cy + radius * 0.64f
        canvas.drawLine(cx - arm, topY, cx + arm, topY, symbolPaint)
        canvas.drawLine(cx, topY - arm, cx, topY + arm, symbolPaint)
        canvas.drawLine(cx - arm, bottomY, cx + arm, bottomY, symbolPaint)

        drawTrack(canvas, cx - radius * 0.64f, cy, radius * 0.23f, previous = true)
        drawTrack(canvas, cx + radius * 0.64f, cy, radius * 0.23f, previous = false)
        drawPlayPause(canvas, cx, cy, radius * 0.19f)
    }

    private fun drawTrack(canvas: Canvas, x: Float, y: Float, size: Float, previous: Boolean) {
        val direction = if (previous) -1f else 1f
        val barX = x + direction * size * 0.78f
        canvas.drawLine(barX, y - size, barX, y + size, symbolPaint)
        val triangle = Path().apply {
            moveTo(x + direction * size * 0.55f, y)
            lineTo(x - direction * size * 0.55f, y - size)
            lineTo(x - direction * size * 0.55f, y + size)
            close()
        }
        canvas.drawPath(triangle, fillPaint)
    }

    private fun drawPlayPause(canvas: Canvas, x: Float, y: Float, size: Float) {
        val triangle = Path().apply {
            moveTo(x - size * 0.9f, y - size)
            lineTo(x + size * 0.1f, y)
            lineTo(x - size * 0.9f, y + size)
            close()
        }
        canvas.drawPath(triangle, fillPaint)
        canvas.drawRoundRect(x + size * 0.25f, y - size, x + size * 0.52f, y + size, size * 0.08f, size * 0.08f, fillPaint)
        canvas.drawRoundRect(x + size * 0.72f, y - size, x + size * 0.99f, y + size, size * 0.08f, size * 0.08f, fillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val radius = wheelRadius()
        val dx = event.x - cx
        val dy = event.y - cy
        val inside = dx * dx + dy * dy <= radius * radius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                pressedZone = nearestZone(event.x, event.y, cx, cy, radius)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pressedZone = if (inside) nearestZone(event.x, event.y, cx, cy, radius) else null
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                clickZone = if (inside) nearestZone(event.x, event.y, cx, cy, radius) else null
                pressedZone = null
                invalidate()
                if (clickZone != null) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedZone = null
                invalidate()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        onControl?.invoke(clickZone ?: Zone.CENTER)
        clickZone = null
        return true
    }

    private fun nearestZone(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Zone {
        var nearest = Zone.CENTER
        var bestDistance = Float.MAX_VALUE
        for (zone in Zone.entries) {
            val (zoneX, zoneY) = zoneCenter(zone, cx, cy, radius)
            val dx = x - zoneX
            val dy = y - zoneY
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                nearest = zone
                bestDistance = distance
            }
        }
        return nearest
    }

    private fun zoneCenter(zone: Zone, cx: Float, cy: Float, radius: Float): Pair<Float, Float> = when (zone) {
        Zone.UP -> cx to cy - radius * 0.64f
        Zone.DOWN -> cx to cy + radius * 0.64f
        Zone.PREVIOUS -> cx - radius * 0.64f to cy
        Zone.NEXT -> cx + radius * 0.64f to cy
        Zone.CENTER -> cx to cy
    }

    private fun wheelRadius(): Float = min(width, height) / 2f - 4f * resources.displayMetrics.density
}
