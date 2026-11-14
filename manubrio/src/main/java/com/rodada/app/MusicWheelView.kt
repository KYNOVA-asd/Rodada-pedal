package com.rodada.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Cinco destinos grandes; cada toque se asigna al centro más cercano. */
class MusicWheelView(context: Context, attrs: android.util.AttributeSet?) : View(context, attrs) {
    enum class Zone { UP, DOWN, PREVIOUS, NEXT, CENTER }

    var onControl: ((Zone) -> Unit)? = null

    /**
     * NONE: música. RINGING: izquierda roja ✕ (rechazar) y derecha verde ✓ (contestar).
     * ACTIVE: izquierda roja ✕ (colgar); si están disponibles, centro 🔊 (altavoz) y
     * derecha micrófono tachado (silenciar).
     */
    enum class CallMode { NONE, RINGING, ACTIVE }

    var speakerAvailable = false
        set(value) {
            field = value
            invalidate()
        }

    var muteAvailable = false
        set(value) {
            field = value
            invalidate()
        }

    var callMode = CallMode.NONE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private val declinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 47, 47) }
    private val answerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 158, 74) }
    private val speakerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 168, 255) }
    private val mutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 124, 0) }
    private var pressedZone: Zone? = null
    private var clickZone: Zone? = null

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 48, 48) }
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 70, 70) }
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
        canvas.drawCircle(cx, cy, radius * 0.36f, gapPaint)
        canvas.drawCircle(cx, cy, radius * 0.30f, centerPaint)

        pressedZone?.let { zone ->
            val (x, y) = zoneCenter(zone, cx, cy, radius)
            canvas.drawCircle(x, y, radius * 0.27f, pressedPaint)
        }

        symbolPaint.strokeWidth = radius * 0.020f
        val arm = radius * 0.105f
        val topY = cy - radius * 0.64f
        val bottomY = cy + radius * 0.64f
        canvas.drawLine(cx - arm, topY, cx + arm, topY, symbolPaint)
        canvas.drawLine(cx, topY - arm, cx, topY + arm, symbolPaint)
        canvas.drawLine(cx - arm, bottomY, cx + arm, bottomY, symbolPaint)

        if (callMode != CallMode.NONE) {
            drawCallButtons(canvas, cx, cy, radius)
        } else {
            drawTrack(canvas, cx - radius * 0.64f, cy, radius * 0.14f, previous = true)
            drawTrack(canvas, cx + radius * 0.64f, cy, radius * 0.14f, previous = false)
        }
        if (!(callMode == CallMode.ACTIVE && speakerAvailable)) drawPlayPause(canvas, cx, cy, radius * 0.12f)
    }

    private fun drawCallButtons(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val left = cx - radius * 0.64f
        val right = cx + radius * 0.64f
        val r = radius * 0.27f
        canvas.drawCircle(left, cy, r, declinePaint)
        val previousWidth = symbolPaint.strokeWidth
        symbolPaint.strokeWidth = radius * 0.05f
        val a = r * 0.45f
        // ✕ rechazar o colgar
        canvas.drawLine(left - a, cy - a, left + a, cy + a, symbolPaint)
        canvas.drawLine(left - a, cy + a, left + a, cy - a, symbolPaint)
        if (callMode == CallMode.RINGING) {
            // ✓ contestar
            canvas.drawCircle(right, cy, r, answerPaint)
            canvas.drawLine(right - a, cy, right - a * 0.2f, cy + a * 0.8f, symbolPaint)
            canvas.drawLine(right - a * 0.2f, cy + a * 0.8f, right + a, cy - a * 0.7f, symbolPaint)
        } else if (speakerAvailable) {
            // 🔊 altavoz, en el centro
            canvas.drawCircle(cx, cy, radius * 0.30f, speakerPaint)
            val s = radius * 0.08f
            val body = Path().apply {
                moveTo(cx - s * 2f, cy - s * 0.8f)
                lineTo(cx - s, cy - s * 0.8f)
                lineTo(cx + s * 0.4f, cy - s * 2f)
                lineTo(cx + s * 0.4f, cy + s * 2f)
                lineTo(cx - s, cy + s * 0.8f)
                lineTo(cx - s * 2f, cy + s * 0.8f)
                close()
            }
            canvas.drawPath(body, fillPaint)
            canvas.drawArc(cx - s * 0.2f, cy - s * 1.4f, cx + s * 2.2f, cy + s * 1.4f, -45f, 90f, false, symbolPaint)
        }
        if (callMode == CallMode.ACTIVE && muteAvailable) {
            // Micrófono tachado: silenciar, a la derecha.
            canvas.drawCircle(right, cy, r, mutePaint)
            val m = r * 0.18f
            canvas.drawRoundRect(right - m, cy - m * 2.6f, right + m, cy + m * 0.6f, m, m, fillPaint)
            canvas.drawArc(right - m * 2f, cy - m * 1.6f, right + m * 2f, cy + m * 1.6f, 0f, 180f, false, symbolPaint)
            canvas.drawLine(right, cy + m * 1.6f, right, cy + m * 2.6f, symbolPaint)
            canvas.drawLine(right - m * 2.6f, cy - m * 2.6f, right + m * 2.6f, cy + m * 2.6f, symbolPaint)
        }
        symbolPaint.strokeWidth = previousWidth
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
        Log.d(TAG, "zona=${clickZone ?: Zone.CENTER} onControl=${onControl != null}")
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

    private fun wheelRadius(): Float = min(
        width * 0.45f,
        height / 2f - 6f * resources.displayMetrics.density
    )

    companion object {
        private const val TAG = "RodadaRueda"
    }
}
