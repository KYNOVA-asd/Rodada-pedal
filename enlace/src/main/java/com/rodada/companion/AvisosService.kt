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
    /** Llamadas activas por clave de notificación (solo en memoria). */
    private data class Llamada(val id: String, var enCurso: Boolean)
    private val llamadas = HashMap<String, Llamada>()
    private var numeroLlamada = 0

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

    /**
     * Al conectar el S40 (o al reiniciarse este servicio): manda la llamada que esté
     * sonando o en curso, para que el S40 no pierda el control a mitad de llamada.
     */
    fun reenviarLlamadasActuales() {
        val activas = try {
            activeNotifications.orEmpty().filter {
                it.packageName in ajustes.apps && it.notification.category == Notification.CATEGORY_CALL
            }
        } catch (_: SecurityException) {
            return
        }
        for (sbn in activas) {
            // Se olvida el estado enviado y se vuelve a evaluar: manda LLAMADA o LLAMADA_CURSO.
            llamadas[sbn.key]?.let { anterior ->
                llamadas.remove(sbn.key)
                if (anterior.enCurso) llamadas[sbn.key] = Llamada(anterior.id, enCurso = false)
            }
            revisarLlamada(sbn)
        }
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
        if (n.category == Notification.CATEGORY_CALL) {
            revisarLlamada(sbn)
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
        if (sbn.key in llamadas) terminarLlamada(sbn.key)
        if (sbn.key == claveNavegacion) {
            claveNavegacion = null
            EnlaceService.actual?.enviar(Mensaje.NavFin)
            Log.d(TAG, "navegación terminada")
        }
    }

    /**
     * Llamada de una app elegida (Teléfono, WhatsApp…), según su CallStyle:
     * - entrante (sonando): LLAMADA; si la app no usa CallStyle, cuenta como entrante
     *   si tiene pantalla completa;
     * - en curso (contestada): LLAMADA_CURSO, con si hay botón de altavoz;
     * - al quitarse la notificación: LLAMADA_FIN.
     */
    private fun revisarLlamada(sbn: StatusBarNotification) {
        val n = sbn.notification
        val tipo = n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val sonando = if (tipo != 0) tipo == Notification.CallStyle.CALL_TYPE_INCOMING else n.fullScreenIntent != null
        val enCurso = tipo == Notification.CallStyle.CALL_TYPE_ONGOING ||
            (tipo == 0 && !sonando && sbn.key in llamadas)
        val actual = llamadas[sbn.key]
        when {
            sonando && actual == null -> {
                val id = (++numeroLlamada).toString()
                llamadas[sbn.key] = Llamada(id, enCurso = false)
                val enviado = EnlaceService.actual?.enviar(Mensaje.Llamada(id, app(sbn), nombre(n))) ?: false
                Log.d(TAG, "llamada entrante de ${sbn.packageName} reenviada=$enviado")
            }
            enCurso && actual?.enCurso != true -> {
                val llamada = actual ?: Llamada((++numeroLlamada).toString(), enCurso = true).also { llamadas[sbn.key] = it }
                llamada.enCurso = true
                val altavoz = buscarAccion(n, Mensaje.Responder.Accion.ALTAVOZ) != null
                val silenciar = buscarAccion(n, Mensaje.Responder.Accion.SILENCIAR) != null
                // Solo los textos de los botones (no hay datos privados), para ajustar las palabras.
                Log.d(TAG, "llamada en curso; botones=${n.actions.orEmpty().map { it.title }}")
                EnlaceService.actual?.enviar(Mensaje.LlamadaEnCurso(llamada.id, app(sbn), nombre(n), altavoz, silenciar))
            }
        }
    }

    private fun app(sbn: StatusBarNotification) = Enlace.recortar(nombreApp(sbn.packageName), Enlace.MAX_APP)

    private fun nombre(n: Notification): String {
        val persona = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        val nombre = persona?.name?.toString()?.trim().orEmpty()
            .ifEmpty { n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim() }
        return Enlace.recortar(nombre, Enlace.MAX_TITULO)
    }

    /** Botón de la notificación para esa acción, buscado por su texto. */
    private fun buscarAccion(n: Notification, accion: Mensaje.Responder.Accion): Notification.Action? {
        val palabras = when (accion) {
            Mensaje.Responder.Accion.CONTESTAR -> listOf("contest", "respond", "atender", "aceptar", "answer")
            Mensaje.Responder.Accion.RECHAZAR -> listOf("rechaz", "declin", "ignorar", "decline")
            Mensaje.Responder.Accion.COLGAR -> listOf("colgar", "finalizar", "terminar", "hang up", "end call")
            Mensaje.Responder.Accion.ALTAVOZ -> listOf("altavoz", "bocina", "speaker")
            Mensaje.Responder.Accion.SILENCIAR -> listOf("silenc", "mute", "micr")
        }
        return n.actions.orEmpty().firstOrNull { a -> palabras.any { it in a.title.toString().lowercase() } }
    }

    /**
     * Aprieta un botón de la notificación de la llamada. Si no lo encuentra por su
     * texto: contestar = el último, rechazar o colgar = el primero (orden de CallStyle);
     * altavoz y silenciar no tienen respaldo (si la app no los ofrece, no se puede).
     */
    fun responderLlamada(id: String, accion: Mensaje.Responder.Accion) {
        val clave = llamadas.entries.firstOrNull { it.value.id == id }?.key ?: return
        val sbn = try {
            activeNotifications.orEmpty().firstOrNull { it.key == clave }
        } catch (_: SecurityException) {
            null
        } ?: return
        val acciones = sbn.notification.actions.orEmpty()
        val boton = buscarAccion(sbn.notification, accion) ?: when (accion) {
            Mensaje.Responder.Accion.CONTESTAR -> acciones.lastOrNull()
            Mensaje.Responder.Accion.RECHAZAR, Mensaje.Responder.Accion.COLGAR -> acciones.firstOrNull()
            Mensaje.Responder.Accion.ALTAVOZ, Mensaje.Responder.Accion.SILENCIAR -> null
        }
        if (boton == null) {
            Log.w(TAG, "llamada: sin botón para $accion")
            return
        }
        // Contestar suele abrir la pantalla de llamada: se permite ese arranque desde aquí.
        val opciones = android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        try {
            boton.actionIntent.send(this, 0, null, null, null, null, opciones)
            Log.d(TAG, "llamada: $accion enviado")
        } catch (e: android.app.PendingIntent.CanceledException) {
            Log.w(TAG, "llamada: el botón ya no es válido")
        }
    }

    private fun terminarLlamada(clave: String) {
        val llamada = llamadas.remove(clave) ?: return
        EnlaceService.actual?.enviar(Mensaje.LlamadaFin(llamada.id))
        Log.d(TAG, "llamada terminada")
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
