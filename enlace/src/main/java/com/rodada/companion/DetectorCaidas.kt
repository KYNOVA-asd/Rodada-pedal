package com.rodada.companion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.rodada.enlace.Bitacora
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Posible caída = golpe fuerte en este teléfono (va en la mochila, pegado al cuerpo)
 * + frenón que avisa el S40 (de rápido a 0), con menos de [VENTANA_MS] entre los dos,
 * + [QUIETUD_MS] casi sin moverse después. Solo escucha el acelerómetro mientras hay
 * un recorrido (armar/desarmar); nada se guarda.
 */
class DetectorCaidas(context: Context, private val alDetectar: () -> Unit) : SensorEventListener {
    private val contexto = context.applicationContext
    private var ultimoPicoMs = 0L
    private val sensores = context.getSystemService(SensorManager::class.java)
    private val acelerometro: Sensor? = sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val principal = Handler(Looper.getMainLooper())
    // Sin esto el acelerómetro deja de llegar cuando el moto duerme con la pantalla apagada.
    private val despierto = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RodadaEnlace:caidas")
        .apply { setReferenceCounted(false) }

    var armado = false
        private set
    private var ultimoGolpeMs = 0L
    private var ultimoFrenonMs = 0L
    private var vigilando = false
    private var movimientos = 0

    private val finQuietud = Runnable {
        vigilando = false
        ultimoGolpeMs = 0L
        ultimoFrenonMs = 0L
        val quieto = movimientos <= MAX_MOVIMIENTOS
        Log.d(TAG, "quietud terminada: movimientos=$movimientos quieto=$quieto")
        Bitacora.anotar(contexto, "QUIETUD", movimientos.toDouble(), "quieto=$quieto")
        if (quieto && armado) alDetectar()
    }

    fun armar() {
        if (armado || acelerometro == null) return
        armado = true
        sensores.registerListener(this, acelerometro, SensorManager.SENSOR_DELAY_GAME, principal)
        despierto.acquire(MAX_ARMADO_MS)
        Log.d(TAG, "armado")
        Bitacora.anotar(contexto, "ARMADO", null, Bitacora.bateria(contexto))
    }

    fun desarmar() {
        if (!armado) return
        armado = false
        sensores.unregisterListener(this)
        principal.removeCallbacks(finQuietud)
        vigilando = false
        ultimoGolpeMs = 0L
        ultimoFrenonMs = 0L
        if (despierto.isHeld) despierto.release()
        Log.d(TAG, "desarmado")
        Bitacora.anotar(contexto, "DESARMADO", null, Bitacora.bateria(contexto))
    }

    /** El S40 avisó de un frenón. */
    fun frenon() {
        if (!armado) return
        ultimoFrenonMs = System.currentTimeMillis()
        Log.d(TAG, "frenón")
        Bitacora.anotar(contexto, "FRENON")
        revisar()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val fuerza = sqrt(x * x + y * y + z * z)
        // Para calibrar: picos medianos (no llegan a golpe), máximo uno cada 2 s.
        val ahora = System.currentTimeMillis()
        if (fuerza > UMBRAL_PICO && fuerza <= UMBRAL_GOLPE && ahora - ultimoPicoMs > 2_000L) {
            ultimoPicoMs = ahora
            Bitacora.anotar(contexto, "PICO", fuerza / SensorManager.GRAVITY_EARTH.toDouble())
        }
        if (fuerza > UMBRAL_GOLPE) {
            if (!vigilando) {
                ultimoGolpeMs = System.currentTimeMillis()
                Bitacora.anotar(contexto, "GOLPE", fuerza / SensorManager.GRAVITY_EARTH.toDouble())
                Log.d(TAG, "golpe ${"%.1f".format(fuerza / SensorManager.GRAVITY_EARTH)} g")
                revisar()
            }
        }
        if (vigilando && abs(fuerza - SensorManager.GRAVITY_EARTH) > UMBRAL_MOVIMIENTO) movimientos++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun revisar() {
        if (vigilando || ultimoGolpeMs == 0L || ultimoFrenonMs == 0L) return
        if (abs(ultimoGolpeMs - ultimoFrenonMs) > VENTANA_MS) return
        vigilando = true
        movimientos = 0
        Log.d(TAG, "golpe + frenón: vigilando quietud")
        Bitacora.anotar(contexto, "VIGILANDO")
        principal.postDelayed(finQuietud, QUIETUD_MS)
    }

    companion object {
        private const val TAG = "RodadaCaidas"
        /** 3.5 g: más que un bache en la mochila, menos que un golpe contra el suelo. */
        private const val UMBRAL_GOLPE = 3.5f * SensorManager.GRAVITY_EARTH
        private const val UMBRAL_PICO = 2.0f * SensorManager.GRAVITY_EARTH
        /** Desviación de la gravedad que cuenta como "se movió". */
        private const val UMBRAL_MOVIMIENTO = 2.5f
        private const val VENTANA_MS = 6_000L
        private const val QUIETUD_MS = 20_000L
        /** ~1.2 s de movimiento en 20 s (a ~50 lecturas por segundo). */
        private const val MAX_MOVIMIENTOS = 60
        private const val MAX_ARMADO_MS = 6 * 60 * 60 * 1000L
    }
}
