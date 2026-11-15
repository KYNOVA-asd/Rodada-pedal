package com.rodada.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.rodada.enlace.Bitacora
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Modo guardia: con la bici estacionada, avisa si la mueven o la inclinan.
 * Al armar espera [ESPERA_MS] (para alejarse) y aprende la posición de reposo;
 * luego dispara si se inclina más de [INCLINACION_DEG] o si hay sacudidas.
 * Mantiene la CPU despierta mientras está armado (va con cargador).
 */
internal class Guardia(context: Context, private val alDetectar: (String) -> Unit) : SensorEventListener {
    enum class Estado { APAGADO, ARMANDO, VIGILANDO }

    private val contexto = context.applicationContext
    private val sensores = context.getSystemService(SensorManager::class.java)
    private val acelerometro: Sensor? = sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val principal = Handler(Looper.getMainLooper())
    private val despierto = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rodada:guardia").apply { setReferenceCounted(false) }

    var estado = Estado.APAGADO
        private set
    private val gravedad = FloatArray(3)
    private var tieneGravedad = false
    private var reposo: FloatArray? = null
    private var sacudidas = 0
    private var primeraSacudidaMs = 0L
    private var inclinadaDesdeMs = 0L
    private var ultimaAlertaMs = 0L

    private val empezar = Runnable {
        reposo = gravedad.copyOf()
        estado = Estado.VIGILANDO
        Bitacora.anotar(contexto, "GUARDIA_VIGILANDO")
    }

    fun armar() {
        if (estado != Estado.APAGADO || acelerometro == null) return
        estado = Estado.ARMANDO
        tieneGravedad = false
        reposo = null
        sensores.registerListener(this, acelerometro, SensorManager.SENSOR_DELAY_GAME, principal)
        despierto.acquire(MAX_MS)
        principal.postDelayed(empezar, ESPERA_MS)
        Bitacora.anotar(contexto, "GUARDIA_ARMADA")
    }

    fun desarmar() {
        if (estado == Estado.APAGADO) return
        estado = Estado.APAGADO
        principal.removeCallbacks(empezar)
        sensores.unregisterListener(this)
        if (despierto.isHeld) despierto.release()
        Bitacora.anotar(contexto, "GUARDIA_APAGADA")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        if (!tieneGravedad) {
            gravedad[0] = x; gravedad[1] = y; gravedad[2] = z
            tieneGravedad = true
        } else {
            gravedad[0] = 0.9f * gravedad[0] + 0.1f * x
            gravedad[1] = 0.9f * gravedad[1] + 0.1f * y
            gravedad[2] = 0.9f * gravedad[2] + 0.1f * z
        }
        val ref = reposo ?: return
        if (estado != Estado.VIGILANDO) return
        val ahora = System.currentTimeMillis()
        if (ahora - ultimaAlertaMs < PAUSA_ENTRE_ALERTAS_MS) return

        // Sacudidas: varias lecturas fuera de la gravedad en poco tiempo.
        val fuerza = sqrt(x * x + y * y + z * z)
        if (abs(fuerza - SensorManager.GRAVITY_EARTH) > UMBRAL_SACUDIDA) {
            if (ahora - primeraSacudidaMs > VENTANA_SACUDIDA_MS) {
                primeraSacudidaMs = ahora
                sacudidas = 0
            }
            if (++sacudidas >= SACUDIDAS_PARA_ALERTA) {
                disparar("sacudida", ahora)
                return
            }
        }
        // Inclinación sostenida respecto a como quedó estacionada.
        if (angulo(ref, gravedad) > INCLINACION_DEG) {
            if (inclinadaDesdeMs == 0L) inclinadaDesdeMs = ahora
            if (ahora - inclinadaDesdeMs > INCLINACION_SOSTENIDA_MS) disparar("inclinada", ahora)
        } else {
            inclinadaDesdeMs = 0L
        }
    }

    private fun disparar(motivo: String, ahora: Long) {
        ultimaAlertaMs = ahora
        sacudidas = 0
        inclinadaDesdeMs = 0L
        Bitacora.anotar(contexto, "GUARDIA_ALERTA", null, motivo)
        alDetectar(motivo)
    }

    /** Tras una alerta la bici quedó en otra posición: esa es la nueva de reposo. */
    fun reaprender() {
        if (estado == Estado.VIGILANDO) reposo = gravedad.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun angulo(a: FloatArray, b: FloatArray): Float {
        val punto = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na == 0f || nb == 0f) return 0f
        return Math.toDegrees(acos((punto / (na * nb)).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    companion object {
        const val ESPERA_MS = 15_000L
        private const val INCLINACION_DEG = 12f
        private const val INCLINACION_SOSTENIDA_MS = 700L
        private const val UMBRAL_SACUDIDA = 3.0f
        private const val SACUDIDAS_PARA_ALERTA = 4
        private const val VENTANA_SACUDIDA_MS = 1_500L
        private const val PAUSA_ENTRE_ALERTAS_MS = 60_000L
        private const val MAX_MS = 24 * 60 * 60 * 1000L
    }
}
