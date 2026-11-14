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

    override fun onCreate() {
        super.onCreate()
        ajustes = Ajustes(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in ajustes.apps) return
        val n = sbn.notification
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
    }

    private fun nombreApp(paquete: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquete, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        paquete
    }

    companion object {
        private const val TAG = "RodadaAvisos"
    }
}
