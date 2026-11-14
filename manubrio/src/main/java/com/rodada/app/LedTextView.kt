package com.rodada.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Texto en matriz de puntos LED azul (misma fuente 5×7 que [LedTickerView]).
 *
 * - Si cabe, queda fijo y centrado, con los puntos tan grandes como permita el alto.
 * - Si no cabe, primero achica los puntos hasta [MIN_ESCALA]; si aun así no cabe, se
 *   desplaza de derecha a izquierda en bucle.
 * - Solo anima (20 cuadros por segundo) mientras se desplaza y la ventana está visible:
 *   con la pantalla apagada o la app en segundo plano no gasta nada.
 */
class LedTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val encendido = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 168, 255) }
    private val apagado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 24, 44) }

    private var columnas = IntArray(0)
    private var posicion = 0
    private var visibleVentana = true

    var text: CharSequence = ""
        set(value) {
            if (value.toString() == field.toString()) return
            field = value
            columnas = LedTickerView.columnas(value.toString())
            posicion = 0
            reiniciar()
        }

    fun setText(resId: Int) {
        text = context.getString(resId)
    }

    init {
        // Permite android:text en el XML (por ejemplo, las etiquetas DISTANCIA y TIEMPO).
        context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text)).use {
            it.getText(0)?.let { inicial -> text = inicial }
        }
    }

    private val cuadro = object : Runnable {
        override fun run() {
            if (!corre()) return
            posicion++
            invalidate()
            postDelayed(this, CUADRO_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reiniciar()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        visibleVentana = visibility == VISIBLE
        reiniciar()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        reiniciar()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(cuadro)
        super.onDetachedFromWindow()
    }

    private fun reiniciar() {
        removeCallbacks(cuadro)
        invalidate()
        if (corre()) postDelayed(cuadro, CUADRO_MS)
    }

    private fun alto() = (height - paddingTop - paddingBottom).toFloat()
    private fun ancho() = (width - paddingLeft - paddingRight).toFloat()

    /** Tamaño de punto: llena el alto; si no cabe a lo ancho, achica hasta la mitad. */
    private fun paso(): Float {
        val porAlto = alto() / FILAS
        if (columnas.isEmpty()) return porAlto
        return maxOf(min(porAlto, ancho() / columnas.size), porAlto * MIN_ESCALA)
    }

    private fun corre(): Boolean {
        val p = paso()
        return visibleVentana && isShown && p > 0f && columnas.size * p > ancho() + 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = paso()
        if (p <= 0f) return
        val visibles = (ancho() / p).toInt()
        val margenX = paddingLeft + (ancho() - visibles * p) / 2f
        val margenY = paddingTop + (alto() - FILAS * p) / 2f
        val radio = p * 0.4f
        val desplaza = columnas.size > visibles
        val ciclo = columnas.size + ESPACIO
        val desde = if (desplaza) posicion - visibles else -(visibles - columnas.size) / 2
        for (c in 0 until visibles) {
            val i = if (desplaza) Math.floorMod(desde + c, ciclo) else desde + c
            val bits = if (i in columnas.indices) columnas[i] else 0
            val x = margenX + c * p + p / 2f
            for (f in 0 until FILAS) {
                val y = margenY + f * p + p / 2f
                canvas.drawCircle(x, y, radio, if (bits and (1 shl f) != 0) encendido else apagado)
            }
        }
    }

    companion object {
        private const val FILAS = 7
        private const val CUADRO_MS = 50L
        private const val ESPACIO = 10
        private const val MIN_ESCALA = 0.35f
    }
}
