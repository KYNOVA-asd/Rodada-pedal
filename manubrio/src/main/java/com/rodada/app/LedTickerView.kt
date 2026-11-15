package com.rodada.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Franja tipo reloj digital: matriz de píxeles LCD de 7 filas,
 * dibujada punto por punto con una fuente 5×7 propia (sin fuentes externas).
 *
 * Ciclo, como un autoestéreo:
 * 1. RELOJ: la hora, fija, [RELOJ_MS].
 * 2. TEXTO: el texto de [mostrar]. Si debe correr y no cabe, pasa una vez de
 *    derecha a izquierda; si cabe o es fijo (PAUSA, SIN MUSICA…), se queda
 *    [TEXTO_MS]. Luego vuelve al reloj.
 * [mostrarUnaVez] interrumpe (por ejemplo, una notificación), pasa una vez y sigue
 * con el texto. Un texto nuevo (canción nueva) se muestra enseguida.
 *
 * Batería: solo anima (20 cuadros por segundo) mientras algo corre; con texto fijo
 * solo hay un temporizador para cambiar de fase. [pausar] lo detiene todo.
 */
class LedTickerView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private enum class Fase { RELOJ, TEXTO, UNA_VEZ }

    private val encendido = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.lcd_ink) }
    private val apagado = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.lcd_ghost) }

    fun setBacklit(backlit: Boolean) {
        encendido.color = context.getColor(if (backlit) R.color.lcd_ink else R.color.lcd_day_ink)
        apagado.color = context.getColor(if (backlit) R.color.lcd_ghost else R.color.lcd_day_ghost)
        invalidate()
    }
    private val formatoHora = DateTimeFormatter.ofPattern("HH:mm")

    private var base = IntArray(0)
    private var fecha = IntArray(0)
    private var baseCorre = false
    private var baseCompacto = false
    private var unaVez = IntArray(0)

    private var fase = Fase.RELOJ
    private var tira = IntArray(0)
    private var corriendo = false
    private var posicion = 0
    private var enMarcha = true

    private val cuadro = object : Runnable {
        override fun run() {
            if (!enMarcha || !corriendo) return
            posicion++
            if (posicion >= tira.size) {
                // Terminó de pasar: notificación → texto; texto → reloj.
                entrar(if (fase == Fase.UNA_VEZ) Fase.TEXTO else Fase.RELOJ)
                return
            }
            invalidate()
            postDelayed(this, CUADRO_MS)
        }
    }

    private val siguienteFase = Runnable {
        entrar(if (fase == Fase.RELOJ) Fase.TEXTO else Fase.RELOJ)
    }

    fun mostrar(texto: String, desplazar: Boolean, compacto: Boolean = false) {
        val nuevas = columnas(texto)
        if (nuevas.contentEquals(base) && desplazar == baseCorre && compacto == baseCompacto) return
        base = nuevas
        baseCorre = desplazar
        baseCompacto = compacto
        if (fase != Fase.UNA_VEZ) entrar(Fase.TEXTO)
    }

    fun mostrarUnaVez(texto: String) {
        unaVez = columnas(texto)
        entrar(Fase.UNA_VEZ)
    }

    fun pausar() {
        enMarcha = false
        removeCallbacks(cuadro)
        removeCallbacks(siguienteFase)
    }

    fun reanudar() {
        enMarcha = true
        entrar(Fase.RELOJ)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(cuadro)
        removeCallbacks(siguienteFase)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        entrar(fase)
    }

    private fun entrar(nueva: Fase) {
        removeCallbacks(cuadro)
        removeCallbacks(siguienteFase)
        fase = if (nueva == Fase.TEXTO && base.isEmpty()) Fase.RELOJ else nueva
        when (fase) {
            Fase.RELOJ -> {
                val ahora = LocalDateTime.now()
                val dia = arrayOf("LU", "MA", "MI", "JU", "VI", "SA", "DO")[ahora.dayOfWeek.value - 1]
                fecha = columnas("%s %02d".format(dia, ahora.dayOfMonth))
                tira = columnas(ahora.format(formatoHora))
                corriendo = false
            }
            Fase.TEXTO -> {
                tira = base
                corriendo = baseCorre && base.size > columnasVisibles()
            }
            Fase.UNA_VEZ -> {
                tira = unaVez
                corriendo = true
            }
        }
        val visibles = columnasVisibles()
        posicion = -visibles
        invalidate()
        if (!enMarcha || width == 0) return
        if (corriendo) {
            postDelayed(cuadro, CUADRO_MS)
        } else {
            postDelayed(siguienteFase, if (fase == Fase.RELOJ) RELOJ_MS else TEXTO_MS)
        }
    }

    private fun alto(): Int = height - paddingTop - paddingBottom

    private fun ancho(): Int = width - paddingLeft - paddingRight

    private fun paso(): Float {
        val original = alto() / FILAS.toFloat()
        return when {
            fase == Fase.RELOJ && tira.isNotEmpty() ->
                minOf(original, ancho() * 0.70f / tira.size.toFloat())
            fase == Fase.TEXTO && baseCompacto && tira.isNotEmpty() ->
                minOf(original, ancho() / tira.size.toFloat())
            else -> original
        }
    }

    private fun columnasVisibles(): Int = if (alto() <= 0) 0 else (ancho() / paso()).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = paso()
        if (p <= 0f) return
        if (fase == Fase.RELOJ) {
            val gap = 8f * resources.displayMetrics.density
            val dateP = if (fecha.isEmpty()) 0f else minOf(
                alto() / (FILAS * 2f),
                (ancho() - tira.size * p - gap) / fecha.size
            )
            val total = fecha.size * dateP + gap + tira.size * p
            val left = paddingLeft + (ancho() - total) / 2f
            if (dateP > 0f) drawCells(canvas, fecha, left, paddingTop + (alto() - FILAS * dateP) / 2f, dateP)
            drawCells(canvas, tira, left + fecha.size * dateP + gap,
                paddingTop + (alto() - FILAS * p) / 2f, p)
            return
        }
        val visibles = columnasVisibles()
        val margenX = paddingLeft + (ancho() - visibles * p) / 2f
        val medio = p * 0.35f
        val esquina = p * 0.07f
        val desde = when {
            corriendo -> posicion
            tira.size <= visibles -> -(visibles - tira.size) / 2
            else -> 0
        }
        for (c in 0 until visibles) {
            val i = desde + c
            val bits = if (i in tira.indices) tira[i] else 0
            val x = margenX + c * p + p / 2f
            for (f in 0 until FILAS) {
                val y = paddingTop + p * f + p / 2f
                canvas.drawRoundRect(x - medio, y - medio, x + medio, y + medio,
                    esquina, esquina, if (bits and (1 shl f) != 0) encendido else apagado)
            }
        }
    }

    private fun drawCells(canvas: Canvas, columns: IntArray, left: Float, top: Float, p: Float) {
        val half = p * 0.35f
        val corner = p * 0.07f
        for (c in columns.indices) {
            val x = left + c * p + p / 2f
            for (f in 0 until FILAS) {
                val y = top + f * p + p / 2f
                canvas.drawRoundRect(x - half, y - half, x + half, y + half,
                    corner, corner, if (columns[c] and (1 shl f) != 0) encendido else apagado)
            }
        }
    }

    companion object {
        private const val FILAS = 7
        private const val CUADRO_MS = 50L
        private const val RELOJ_MS = 6_000L
        private const val TEXTO_MS = 4_000L

        /** Columnas del texto: cada entero tiene 7 bits, el bit 0 es la fila de arriba. */
        fun columnas(texto: String): IntArray {
            val limpio = normalizar(texto)
            val salida = ArrayList<Int>(limpio.length * 6)
            for (ch in limpio) {
                val filas = FUENTE[ch] ?: FUENTE.getValue('?')
                for (col in 4 downTo 0) {
                    var bits = 0
                    for (f in 0 until FILAS) if (filas[f] shr col and 1 == 1) bits = bits or (1 shl f)
                    salida.add(bits)
                }
                salida.add(0)
            }
            return salida.toIntArray()
        }

        /** Mayúsculas, sin acentos (salvo Ñ), solo caracteres de la fuente. */
        private fun normalizar(texto: String): String {
            // \p{Cf}: caracteres invisibles de formato (por ejemplo, los que rodean un
            // número de teléfono); si no se quitan, salen como "?".
            val conMarca = texto.replace(Regex("\\p{Cf}"), "").uppercase()
                .replace('–', '-').replace('—', '-').replace('_', ' ').replace('×', 'X')
                .replace('|', '/').replace('[', '(').replace(']', ')').replace('·', '-')
                .replace('Ñ', '\u0001')
            val sinAcentos = Normalizer.normalize(conMarca, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace('\u0001', 'Ñ')
            return sinAcentos.map { if (it in FUENTE) it else if (it.isWhitespace()) ' ' else '?' }
                .joinToString("")
        }

        /** Fuente 5×7 propia: 7 filas de 5 bits (bit 4 = columna izquierda). */
        private val FUENTE: Map<Char, IntArray> = mapOf(
            'A' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x1F, 0x11, 0x11),
            'B' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
            'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
            'D' to intArrayOf(0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C),
            'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
            'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
            'G' to intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F),
            'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
            'I' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
            'J' to intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C),
            'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
            'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
            'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
            'N' to intArrayOf(0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11),
            'Ñ' to intArrayOf(0x0E, 0x00, 0x11, 0x19, 0x15, 0x13, 0x11),
            'O' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
            'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
            'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
            'R' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
            'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
            'T' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
            'U' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
            'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
            'W' to intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A),
            'X' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
            'Y' to intArrayOf(0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04),
            'Z' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F),
            '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
            '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
            '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
            '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
            '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
            '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
            '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
            '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
            '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
            '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
            ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
            '-' to intArrayOf(0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00),
            '.' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C),
            ',' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x0C, 0x04, 0x08),
            ':' to intArrayOf(0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00),
            '!' to intArrayOf(0x04, 0x04, 0x04, 0x04, 0x00, 0x00, 0x04),
            '?' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x00, 0x04),
            '\'' to intArrayOf(0x0C, 0x04, 0x08, 0x00, 0x00, 0x00, 0x00),
            '"' to intArrayOf(0x0A, 0x0A, 0x0A, 0x00, 0x00, 0x00, 0x00),
            '(' to intArrayOf(0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02),
            ')' to intArrayOf(0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08),
            '/' to intArrayOf(0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x00),
            '&' to intArrayOf(0x0C, 0x12, 0x14, 0x08, 0x15, 0x12, 0x0D),
            '+' to intArrayOf(0x00, 0x04, 0x04, 0x1F, 0x04, 0x04, 0x00),
            '#' to intArrayOf(0x0A, 0x0A, 0x1F, 0x0A, 0x1F, 0x0A, 0x0A),
            '¿' to intArrayOf(0x04, 0x00, 0x04, 0x08, 0x10, 0x11, 0x0E),
            '¡' to intArrayOf(0x04, 0x00, 0x00, 0x04, 0x04, 0x04, 0x04),
            '→' to intArrayOf(0x00, 0x04, 0x02, 0x1F, 0x02, 0x04, 0x00),
            '←' to intArrayOf(0x00, 0x04, 0x08, 0x1F, 0x08, 0x04, 0x00),
            '↑' to intArrayOf(0x04, 0x0E, 0x15, 0x04, 0x04, 0x04, 0x04),
            '↓' to intArrayOf(0x04, 0x04, 0x04, 0x04, 0x15, 0x0E, 0x04),
            '✉' to intArrayOf(0x00, 0x1F, 0x1B, 0x15, 0x11, 0x1F, 0x00)
        )
    }
}
