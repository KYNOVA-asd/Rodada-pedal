package com.rodada.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.rodada.enlace.CanalEnlace
import com.rodada.enlace.Enlace
import com.rodada.enlace.Mensaje
import com.rodada.enlace.SesionMusica
import java.io.IOException

/**
 * Servicio en primer plano que escucha al S40 por RFCOMM. El hilo de escucha
 * queda bloqueado en accept() (sin sondeo); al llegar una conexión nueva
 * reemplaza a la anterior.
 */
@SuppressLint("MissingPermission")
class EnlaceService : Service() {
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var voz: Voz
    @Volatile private var servidor: BluetoothServerSocket? = null
    @Volatile private var activo = false
    private var canal: CanalEnlace? = null
    /** Lo que suena en el Motorola; manda CANCION al S40 solo cuando cambia. */
    private lateinit var musica: SesionMusica

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        actual = this
        voz = Voz(this)
        musica = SesionMusica(this, android.content.ComponentName(this, AvisosService::class.java)) { cancion ->
            canal?.enviar(cancion)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL_NOTIFICACION, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID_NOTIFICACION, notificacion(getString(R.string.state_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (!activo) escuchar()
        musica.iniciar()
        return START_STICKY
    }

    private fun escuchar() {
        val adaptador = getSystemService(BluetoothManager::class.java)?.adapter
        if (adaptador == null || !adaptador.isEnabled) {
            ponerEstado(EnlaceEstado.Estado.Error(R.string.error_bluetooth_off))
            stopSelf()
            return
        }
        activo = true
        ponerEstado(EnlaceEstado.Estado.Esperando)
        Thread({
            try {
                val socketServidor = adaptador.listenUsingRfcommWithServiceRecord(Enlace.NOMBRE_SERVICIO, Enlace.UUID)
                servidor = socketServidor
                while (activo) {
                    val socket = socketServidor.accept()
                    principal.post { if (activo) conectar(socket) else socket.close() }
                }
            } catch (e: IOException) {
                Log.d(TAG, "escucha terminó: ${e.javaClass.simpleName}")
                principal.post {
                    if (activo) {
                        ponerEstado(EnlaceEstado.Estado.Error(R.string.error_listen))
                        stopSelf()
                    }
                }
            }
        }, "RodadaEnlaceEscucha").start()
    }

    private fun conectar(socket: android.bluetooth.BluetoothSocket) {
        canal?.cerrar()
        val nombre = socket.remoteDevice.name ?: socket.remoteDevice.address
        lateinit var nuevo: CanalEnlace
        nuevo = CanalEnlace(socket, ::recibir) {
            if (canal === nuevo) {
                canal = null
                if (activo) ponerEstado(EnlaceEstado.Estado.Esperando)
            }
        }
        canal = nuevo
        nuevo.iniciar()
        nuevo.enviar(Mensaje.Hola(Enlace.VERSION, android.os.Build.MODEL))
        // Si el acceso a notificaciones llegó después, se reintenta al conectar.
        musica.iniciar()
        nuevo.enviar(musica.actual)
        AvisosService.actual?.reenviarNavegacionActual()
        Log.d(TAG, "conectado con ${socket.remoteDevice.address}")
        ponerEstado(EnlaceEstado.Estado.Conectado(nombre))
    }

    /** Manda un mensaje al S40 si hay enlace. Llamar desde el hilo principal. */
    fun enviar(mensaje: Mensaje): Boolean {
        val c = canal ?: return false
        c.enviar(mensaje)
        return true
    }

    fun decir(texto: String) = voz.decir(texto)

    private fun recibir(mensaje: Mensaje) {
        Log.d(TAG, "recibido $mensaje")
        when (mensaje) {
            is Mensaje.Decir -> voz.decir(mensaje.texto)
            is Mensaje.Hola, is Mensaje.Notif, is Mensaje.Cancion, is Mensaje.Nav, Mensaje.NavFin -> Unit
        }
    }

    private fun ponerEstado(estado: EnlaceEstado.Estado) {
        EnlaceEstado.estado = estado
        if (!activo) return
        val texto = when (estado) {
            is EnlaceEstado.Estado.Conectado -> getString(R.string.state_connected, estado.nombre)
            else -> getString(R.string.state_waiting)
        }
        getSystemService(NotificationManager::class.java).notify(ID_NOTIFICACION, notificacion(texto))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL_NOTIFICACION)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (actual === this) actual = null
        activo = false
        try {
            servidor?.close()
        } catch (_: IOException) {
        }
        servidor = null
        canal?.cerrar()
        canal = null
        musica.detener()
        voz.cerrar()
        if (EnlaceEstado.estado !is EnlaceEstado.Estado.Error) EnlaceEstado.estado = EnlaceEstado.Estado.Apagado
        super.onDestroy()
    }

    companion object {
        /** El servicio en marcha, para que AvisosService mande por el enlace (mismo proceso). */
        var actual: EnlaceService? = null
            private set
        private const val TAG = "RodadaEnlace"
        private const val CANAL_NOTIFICACION = "enlace"
        private const val ID_NOTIFICACION = 1
    }
}
