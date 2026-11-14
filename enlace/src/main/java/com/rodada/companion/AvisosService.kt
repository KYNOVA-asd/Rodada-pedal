package com.rodada.companion

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.rodada.enlace.Enlace
import com.rodada.enlace.Mensaje

/**
 * Lee las notificaciones del Motorola y reenvía al S40 solo las de las apps
 * elegidas. El contenido no se guarda en disco ni en logs; solo se recuerda
 * en memoria un resumen numérico por notificación para no reenviar repetidas.
 */
class AvisosService : NotificationListenerService() {
    private lateinit var ajustes: Ajustes
    private val ultimos = HashMap<String, Int>()
    private var claveNavegacion: String? = null

    override fun onCreate() {
        super.onCreate()
        ajustes = Ajustes(this)
    }

    override fun onListenerConnected() {
        actual = this
    }

    override fun onListenerDisconnected() {
        if (actual === this) actual = null
    }

    override fun onDestroy() {
        if (actual === this) actual = null
        super.onDestroy()
    }

    /** Al conectar el S40: manda la indicación de Maps vigente sin esperar a que cambie. */
    fun reenviarNavegacionActual() {
        if (PAQUETE_MAPS !in ajustes.apps) return
        val activa = try {
            activeNotifications.orEmpty().firstOrNull { esNavegacion(it) }
        } catch (_: SecurityException) {
            null
        } ?: return
        ultimos.remove(activa.key)
        reenviarNavegacion(activa)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in ajustes.apps) return
        val n = sbn.notification
        if (esNavegacion(sbn)) {
            reenviarNavegacion(sbn)
            return
        }
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return
        val titulo = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val texto = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (titulo.isEmpty() && texto.isEmpty()) return
        val huella = (titulo + "\u0000" + texto).hashCode()
        if (ultimos[sbn.key] == huella) return
        ultimos[sbn.key] = huella

        val app = nombreApp(sbn.packageName)
        val mensaje = Mensaje.Notif(
            Enlace.recortar(app, Enlace.MAX_APP),
            Enlace.recortar(titulo, Enlace.MAX_TITULO),
            Enlace.recortar(texto, Enlace.MAX_TEXTO)
        )
        val servicio = EnlaceService.actual
        val enviado = servicio?.enviar(mensaje) ?: false
        Log.d(TAG, "notificación de ${sbn.packageName} reenviada=$enviado")
        if (ajustes.leerTitulo && titulo.isNotEmpty()) servicio?.decir(getString(R.string.voice_notification, app, mensaje.titulo))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        ultimos.remove(sbn.key)
        if (sbn.key == claveNavegacion) {
            claveNavegacion = null
            EnlaceService.actual?.enviar(Mensaje.NavFin)
            Log.d(TAG, "navegación terminada")
        }
    }

    /** Notificación fija de navegación paso a paso de Google Maps. */
    private fun esNavegacion(sbn: StatusBarNotification): Boolean =
        sbn.packageName == PAQUETE_MAPS && (
            sbn.notification.category == Notification.CATEGORY_NAVIGATION ||
                sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
            )

    /** Manda distancia e instrucción solo cuando cambian; Maps la actualiza seguido. */
    private fun reenviarNavegacion(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        // Maps pone la distancia al giro en el título (a veces vacío), la instrucción en
        // el texto y el resumen ("3 min · 800 m · Llegada: …") en el subtexto.
        val distancia = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val resumen = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        val instruccion = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (distancia.isEmpty() && instruccion.isEmpty() && resumen.isEmpty()) return
        val huella = listOf(distancia, instruccion, resumen).hashCode()
        if (ultimos[sbn.key] == huella) return
        ultimos[sbn.key] = huella
        claveNavegacion = sbn.key
        val enviado = EnlaceService.actual?.enviar(
            Mensaje.Nav(
                Enlace.recortar(distancia, Enlace.MAX_TITULO),
                Enlace.recortar(instruccion, Enlace.MAX_TEXTO),
                Enlace.recortar(resumen, Enlace.MAX_TITULO)
            )
        ) ?: false
        Log.d(TAG, "navegación reenviada=$enviado")
    }

    private fun nombreApp(paquete: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquete, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        paquete
    }

    companion object {
        /** El servicio conectado, para que EnlaceService pida la navegación vigente. */
        var actual: AvisosService? = null
            private set
        private const val TAG = "RodadaAvisos"
        private const val PAQUETE_MAPS = "com.google.android.apps.maps"
    }
}
