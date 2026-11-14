package com.rodada.enlace

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Lee la sesión de música activa del teléfono (título, artista y si suena) con
 * MediaSessionManager. Avisa solo cuando cambia algo, sin sondeo: se basa en los
 * callbacks del sistema. Necesita que [oyente] (un NotificationListenerService
 * de la app) tenga el acceso a notificaciones concedido.
 */
class SesionMusica(
    context: Context,
    private val oyente: ComponentName,
    private val alCambiar: (Mensaje.Cancion) -> Unit
) {
    private val principal = Handler(Looper.getMainLooper())
    private val gestor = context.getSystemService(MediaSessionManager::class.java)
    private var controlador: MediaController? = null
    /** Todas las sesiones activas escuchadas, para cambiar a la que empiece a sonar. */
    private var escuchados: List<MediaController> = emptyList()
    private var activa = false

    var actual = Mensaje.Cancion(Mensaje.Cancion.Estado.NADA, "", "")
        private set

    private val alCambiarSesiones = MediaSessionManager.OnActiveSessionsChangedListener { lista ->
        elegir(lista.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = elegir(escuchados)
        override fun onMetadataChanged(metadata: MediaMetadata?) = publicar()
        override fun onSessionDestroyed() = elegir(sesiones())
    }

    /** false si falta el acceso a notificaciones. */
    fun iniciar(): Boolean {
        if (activa) return true
        return try {
            gestor.addOnActiveSessionsChangedListener(alCambiarSesiones, oyente, principal)
            activa = true
            elegir(sesiones())
            true
        } catch (e: SecurityException) {
            Log.d(TAG, "sin acceso a notificaciones")
            false
        }
    }

    fun detener() {
        if (!activa) return
        activa = false
        gestor.removeOnActiveSessionsChangedListener(alCambiarSesiones)
        escuchados.forEach { it.unregisterCallback(callback) }
        escuchados = emptyList()
        controlador = null
    }

    private fun sesiones(): List<MediaController> = try {
        gestor.getActiveSessions(oyente)
    } catch (_: SecurityException) {
        emptyList()
    }

    /**
     * Entre las que suenan, la que cambió de estado más recientemente (por ejemplo,
     * YouTube Music y no una pestaña de YouTube olvidada en el navegador):
     * 1. la que ya se mostraba, si sigue sonando (evita saltar entre dos);
     * 2. una que suene y tenga canción con duración (las pestañas del navegador no);
     * 3. cualquiera que suene; 4. la de mayor prioridad del sistema.
     */
    private fun elegir(lista: List<MediaController>) {
        if (!activa) return
        if (lista.map { it.sessionToken } != escuchados.map { it.sessionToken }) {
            escuchados.forEach { it.unregisterCallback(callback) }
            lista.forEach { it.registerCallback(callback, principal) }
            escuchados = lista
        }
        val sonando = lista.filter { suena(it.playbackState) }
        controlador = sonando.firstOrNull { it.sessionToken == controlador?.sessionToken }
            ?: sonando.firstOrNull { (it.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L }
            ?: sonando.firstOrNull()
            ?: lista.firstOrNull { it.sessionToken == controlador?.sessionToken }
            ?: lista.firstOrNull()
        publicar()
    }

    private fun publicar() {
        val c = controlador
        val datos = c?.metadata
        val titulo = datos?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artista = (datos?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: datos?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty().trim()
        val estado = when {
            c == null || titulo.isEmpty() -> Mensaje.Cancion.Estado.NADA
            suena(c.playbackState) -> Mensaje.Cancion.Estado.REPRODUCIENDO
            else -> Mensaje.Cancion.Estado.PAUSA
        }
        val nueva = if (estado == Mensaje.Cancion.Estado.NADA) {
            Mensaje.Cancion(estado, "", "")
        } else {
            Mensaje.Cancion(estado, Enlace.recortar(artista, Enlace.MAX_TITULO), Enlace.recortar(titulo, Enlace.MAX_TITULO))
        }
        if (nueva == actual) return
        actual = nueva
        alCambiar(nueva)
    }

    private fun suena(state: PlaybackState?): Boolean = when (state?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
        else -> false
    }

    companion object {
        private const val TAG = "RodadaMusica"
    }
}
