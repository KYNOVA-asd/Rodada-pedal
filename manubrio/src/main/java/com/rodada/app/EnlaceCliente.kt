package com.rodada.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rodada.enlace.CanalEnlace
import com.rodada.enlace.Enlace
import com.rodada.enlace.Mensaje
import java.io.IOException

/**
 * Lado del S40 del enlace con Rodada Enlace (Motorola). Se conecta por RFCOMM
 * al Motorola emparejado. Si se cae, reintenta unas pocas veces con espera
 * creciente y luego se queda quieto hasta que el Motorola vuelve a aparecer
 * por Bluetooth (ACL_CONNECTED). Sin sondeos constantes.
 *
 * Requiere BLUETOOTH_CONNECT concedido.
 */
@SuppressLint("MissingPermission")
class EnlaceCliente(
    context: Context,
    private val direccion: String,
    private val alCambiar: (Estado) -> Unit,
    private val alRecibir: (Mensaje) -> Unit
) {
    enum class Estado { CONECTANDO, CONECTADO, DESCONECTADO }

    private val appContext = context.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private val adaptador = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private var canal: CanalEnlace? = null
    private var activo = false
    private var conectando = false
    private var intento = 0
    var estado = Estado.DESCONECTADO
        private set
    val conectado: Boolean
        get() = estado == Estado.CONECTADO

    private val reintento = Runnable { conectar() }

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            if (device?.address == direccion && estado == Estado.DESCONECTADO) {
                Log.d(TAG, "Motorola visible otra vez; reintento")
                intento = 0
                principal.removeCallbacks(reintento)
                principal.postDelayed(reintento, 1_000L)
            }
        }
    }

    fun iniciar() {
        if (activo) return
        activo = true
        actual = this
        appContext.registerReceiver(
            receptor, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), Context.RECEIVER_NOT_EXPORTED
        )
        intento = 0
        conectar()
    }

    /** Reintento manual (por ejemplo, al elegir una opción del menú). */
    fun reintentar() {
        if (!activo || estado != Estado.DESCONECTADO) return
        intento = 0
        principal.removeCallbacks(reintento)
        conectar()
    }

    fun enviar(mensaje: Mensaje): Boolean {
        val c = canal ?: return false
        c.enviar(mensaje)
        return true
    }

    fun detener() {
        if (!activo) return
        activo = false
        if (actual === this) actual = null
        principal.removeCallbacks(reintento)
        try {
            appContext.unregisterReceiver(receptor)
        } catch (_: IllegalArgumentException) {
        }
        canal?.cerrar()
        canal = null
        estado = Estado.DESCONECTADO
        Log.d(TAG, "enlace liberado")
    }

    private fun conectar() {
        if (!activo || conectando || canal != null) return
        val dispositivo = adaptador?.takeIf { it.isEnabled }?.bondedDevices.orEmpty()
            .firstOrNull { it.address == direccion }
        if (dispositivo == null) {
            cambiar(Estado.DESCONECTADO)
            return
        }
        conectando = true
        cambiar(Estado.CONECTANDO)
        Thread({
            val socket = try {
                dispositivo.createRfcommSocketToServiceRecord(Enlace.UUID).also { it.connect() }
            } catch (e: IOException) {
                Log.d(TAG, "no conectó: ${e.javaClass.simpleName}")
                null
            }
            principal.post {
                conectando = false
                if (socket == null) {
                    programarReintento()
                } else if (!activo) {
                    try { socket.close() } catch (_: IOException) {}
                } else {
                    abrir(socket)
                }
            }
        }, "RodadaEnlaceConecta").start()
    }

    private fun abrir(socket: android.bluetooth.BluetoothSocket) {
        lateinit var nuevo: CanalEnlace
        nuevo = CanalEnlace(socket, alRecibir) {
            if (canal === nuevo) {
                canal = null
                Log.d(TAG, "enlace caído")
                intento = 0
                programarReintento()
            }
        }
        canal = nuevo
        intento = 0
        nuevo.iniciar()
        nuevo.enviar(Mensaje.Hola(Enlace.VERSION, "S40"))
        UbicacionS40.leer(appContext)?.let { nuevo.enviar(it) }
        // El Motorola vigila caídas solo durante un recorrido.
        nuevo.enviar(Mensaje.Recorrido(RideState.running))
        Log.d(TAG, "enlace conectado")
        cambiar(Estado.CONECTADO)
    }

    private fun programarReintento() {
        cambiar(Estado.DESCONECTADO)
        if (!activo || intento >= ESPERAS_MS.size) return
        val espera = ESPERAS_MS[intento++]
        principal.removeCallbacks(reintento)
        principal.postDelayed(reintento, espera)
    }

    private fun cambiar(nuevo: Estado) {
        if (estado == nuevo) return
        estado = nuevo
        alCambiar(nuevo)
    }

    companion object {
        private const val TAG = "RodadaEnlace"
        @Volatile private var actual: EnlaceCliente? = null

        fun enviarUbicacion(punto: Mensaje.Ubicacion): Boolean =
            actual?.takeIf { it.conectado }?.enviar(punto) == true

        /** Para el servicio del recorrido: manda si hay enlace, si no lo ignora. */
        fun enviarSiConectado(mensaje: Mensaje): Boolean =
            actual?.takeIf { it.conectado }?.enviar(mensaje) == true
        /** Pocos reintentos y luego silencio hasta que el Motorola reaparezca. */
        private val ESPERAS_MS = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L)
    }
}
