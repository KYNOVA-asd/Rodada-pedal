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
import com.rodada.enlace.Bitacora
import com.rodada.enlace.CanalEnlace
import com.rodada.enlace.Enlace
import com.rodada.enlace.Mensaje
import com.rodada.enlace.SesionMusica
import com.rodada.enlace.Timbre
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
    /** Suena cuando el S40 busca este teléfono. */
    private lateinit var timbre: Timbre
    /** Caídas: detector (solo durante el recorrido) y su alarma. */
    private lateinit var detector: DetectorCaidas
    private lateinit var alarmaCaida: Timbre
    private var caidaPrueba = false
    private val terminarCuenta = Runnable { avisarContacto() }
    /** Clima durante el recorrido (lo único que sale a internet). */
    private lateinit var clima: Clima

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        actual = this
        voz = Voz(this)
        timbre = Timbre(this)
        timbre.alDetener = {
            EnlaceEstado.buscando = false
            getSystemService(NotificationManager::class.java).cancel(ID_BUSCAR)
            canal?.enviar(Mensaje.BuscarFin)
        }
        clima = Clima(this, pedirUbicacion = { enviar(Mensaje.PedirUbicacion) }) { enviar(it) }
        alarmaCaida = Timbre(this)
        detector = DetectorCaidas(this) { iniciarAlarmaCaida(prueba = false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL_CAIDA, getString(R.string.fall_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
        musica = SesionMusica(this, android.content.ComponentName(this, AvisosService::class.java)) { cancion ->
            canal?.enviar(cancion)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL_NOTIFICACION, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // Canal alto para "te están buscando": puede encender la pantalla con aviso a pantalla completa.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL_BUSCAR, getString(R.string.find_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR_TIMBRE) {
            timbre.detener()
            return START_STICKY
        }
        if (intent?.action == ACCION_ESTOY_BIEN) {
            estoyBien(avisarS40 = true)
            return START_STICKY
        }
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
                // Sin S40 no llegan frenones: no tiene caso gastar batería vigilando.
                detector.desarmar()
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
        AvisosService.actual?.reenviarLlamadasActuales()
        clima.reciente()?.let { nuevo.enviar(it) }
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

    /** El S40 busca este teléfono: alarma fuerte y una notificación para detenerla. */
    private fun sonarTimbre() {
        timbre.sonar()
        EnlaceEstado.buscando = true
        val parar = PendingIntent.getService(
            this, 1, Intent(this, EnlaceService::class.java).setAction(ACCION_PARAR_TIMBRE),
            PendingIntent.FLAG_IMMUTABLE
        )
        // La pantalla de Rodada Enlace con "TE ESTÁN BUSCANDO", encima del bloqueo.
        val pantalla = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_BUSCANDO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val aviso = Notification.Builder(this, CANAL_BUSCAR)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.find_ringing_title))
            .setContentText(getString(R.string.find_ringing_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pantalla)
            .setFullScreenIntent(pantalla, true)
            .addAction(Notification.Action.Builder(null, getString(R.string.find_stop), parar).build())
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_BUSCAR, aviso)
    }

    /** Botón "Detener" de la pantalla. */
    fun dejarDeSonar() = timbre.detener()

    /** Botón "Buscar S40" de la pantalla: false si no hay enlace. */
    fun buscarS40(): Boolean = enviar(Mensaje.Buscar)

    /** Solicita una lectura puntual; el S40 responde cuando tiene un fix válido. */
    fun solicitarUbicacion(): Boolean = enviar(Mensaje.PedirUbicacion)

    private fun recibir(mensaje: Mensaje) {
        Log.d(TAG, "recibido $mensaje")
        when (mensaje) {
            is Mensaje.Ubicacion -> {
                UbicacionGuardada.guardar(this, mensaje)
                clima.nuevaUbicacion()
            }
            is Mensaje.Decir -> voz.decir(mensaje.texto)
            Mensaje.Buscar -> sonarTimbre()
            Mensaje.BuscarFin -> timbre.detener()
            is Mensaje.Responder -> AvisosService.actual?.responderLlamada(mensaje.id, mensaje.accion)
            is Mensaje.Recorrido -> if (mensaje.activo) {
                detector.armar()
                clima.iniciarRecorrido()
            } else {
                detector.desarmar()
                clima.terminarRecorrido()
            }
            Mensaje.Frenon -> detector.frenon()
            Mensaje.PedirClima -> clima.pedirAhora()
            Mensaje.CaidaOk -> estoyBien(avisarS40 = false)
            Mensaje.CaidaAyuda -> if (EnlaceEstado.caida) {
                Bitacora.anotar(this, "AYUDA")
                principal.removeCallbacks(terminarCuenta)
                avisarContacto()
            }
            is Mensaje.Hola, is Mensaje.Notif, is Mensaje.Cancion, is Mensaje.Nav, Mensaje.NavFin, is Mensaje.Llamada, is Mensaje.LlamadaFin, is Mensaje.LlamadaEnCurso, Mensaje.PedirUbicacion,
            is Mensaje.Caida, is Mensaje.CaidaAviso, is Mensaje.Clima -> Unit
        }
    }

    /** Posible caída: alarma en los dos teléfonos y cuenta regresiva para avisar. */
    fun iniciarAlarmaCaida(prueba: Boolean) {
        if (EnlaceEstado.caida) return
        caidaPrueba = prueba
        EnlaceEstado.caida = true
        Bitacora.anotar(this, "ALARMA_CAIDA", null, "prueba=$prueba")
        alarmaCaida.sonar(DURACION_CAIDA_MS + 2_000L)
        val bien = PendingIntent.getService(
            this, 3, Intent(this, EnlaceService::class.java).setAction(ACCION_ESTOY_BIEN),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pantalla = PendingIntent.getActivity(
            this, 4,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAIDA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val aviso = Notification.Builder(this, CANAL_CAIDA)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(if (prueba) R.string.fall_title_test else R.string.fall_title))
            .setContentText(getString(R.string.fall_text, DURACION_CAIDA_MS / 1000))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pantalla)
            .setFullScreenIntent(pantalla, true)
            .addAction(Notification.Action.Builder(null, getString(R.string.fall_ok), bien).build())
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_CAIDA, aviso)
        enviar(Mensaje.Caida((DURACION_CAIDA_MS / 1000).toInt(), prueba))
        voz.decir(getString(R.string.fall_voice))
        principal.removeCallbacks(terminarCuenta)
        principal.postDelayed(terminarCuenta, DURACION_CAIDA_MS)
    }

    /** "ESTOY BIEN" desde el moto (avisa al S40) o desde el S40. */
    fun estoyBien(avisarS40: Boolean) {
        if (!EnlaceEstado.caida) return
        principal.removeCallbacks(terminarCuenta)
        alarmaCaida.detener()
        getSystemService(NotificationManager::class.java).cancel(ID_CAIDA)
        EnlaceEstado.caida = false
        Bitacora.anotar(this, "ESTOY_BIEN", null, if (avisarS40) "moto" else "s40")
        if (avisarS40) enviar(Mensaje.CaidaOk)
    }

    /** Nadie apagó la alarma: SMS con la ubicación y llamada (en la prueba, nada). */
    private fun avisarContacto() {
        alarmaCaida.detener()
        getSystemService(NotificationManager::class.java).cancel(ID_CAIDA)
        EnlaceEstado.caida = false
        val ajustes = Ajustes(this)
        val numero = ajustes.contactoNumero
        val nombre = ajustes.contactoNombre ?: numero ?: ""
        if (caidaPrueba || numero == null) {
            enviar(Mensaje.CaidaAviso(false))
            avisoFinal(getString(if (numero == null) R.string.fall_no_contact else R.string.fall_test_done, nombre))
            return
        }
        val sms = mandarSms(numero, textoCaida(prueba = false))
        val llamada = llamar(numero)
        Bitacora.anotar(this, "AVISO", null, "sms=$sms llamada=$llamada")
        enviar(Mensaje.CaidaAviso(sms || llamada))
        avisoFinal(getString(if (sms || llamada) R.string.fall_sent else R.string.fall_failed, nombre))
    }

    /** Botón "Mandar SMS de prueba": mensaje marcado como PRUEBA, sin llamar. */
    fun smsDePrueba(): Boolean {
        val numero = Ajustes(this).contactoNumero ?: return false
        return mandarSms(numero, textoCaida(prueba = true))
    }

    /** Botón "Llamada de prueba": la misma llamada que haría una caída real. */
    fun llamadaDePrueba(): Boolean {
        val numero = Ajustes(this).contactoNumero ?: return false
        return llamar(numero)
    }

    private fun textoCaida(prueba: Boolean): String {
        val punto = UbicacionGuardada.leer(this)
        val donde = if (punto == null) {
            getString(R.string.fall_sms_no_location)
        } else {
            val minutos = ((System.currentTimeMillis() - punto.horaMs) / 60_000L).coerceAtLeast(0)
            getString(
                R.string.fall_sms_location, minutos, punto.precisionMetros.toInt(),
                "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, punto.latitud, punto.longitud)
            )
        }
        return getString(if (prueba) R.string.fall_sms_test else R.string.fall_sms, donde)
    }

    private fun mandarSms(numero: String, texto: String): Boolean = try {
        val sms = getSystemService(android.telephony.SmsManager::class.java)
        sms.sendMultipartTextMessage(numero, null, sms.divideMessage(texto), null, null)
        Log.d(TAG, "sms enviado")
        true
    } catch (e: Exception) {
        Log.w(TAG, "sms falló: ${e.javaClass.simpleName}")
        false
    }

    private fun llamar(numero: String): Boolean = try {
        // TelecomManager llama sin abrir pantalla, así funciona con el moto bloqueado.
        getSystemService(android.telecom.TelecomManager::class.java)
            .placeCall(android.net.Uri.fromParts("tel", numero, null), android.os.Bundle())
        true
    } catch (e: Exception) {
        Log.w(TAG, "llamada falló: ${e.javaClass.simpleName}")
        false
    }

    private fun avisoFinal(texto: String) {
        val aviso = Notification.Builder(this, CANAL_CAIDA)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_CAIDA_FIN, aviso)
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
        timbre.detener()
        detector.desarmar()
        clima.terminarRecorrido()
        principal.removeCallbacks(terminarCuenta)
        alarmaCaida.detener()
        EnlaceEstado.caida = false
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
        private const val ID_BUSCAR = 2
        private const val CANAL_BUSCAR = "buscar"
        private const val ACCION_PARAR_TIMBRE = "com.rodada.companion.PARAR_TIMBRE"
        private const val ACCION_ESTOY_BIEN = "com.rodada.companion.ESTOY_BIEN"
        private const val CANAL_CAIDA = "caida"
        private const val ID_CAIDA = 3
        private const val ID_CAIDA_FIN = 4
        /** Tiempo para apagar la alarma antes de avisar (elegido por el usuario). */
        const val DURACION_CAIDA_MS = 60_000L
    }
}
