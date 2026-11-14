package com.rodada.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * TextToSpeech del sistema en español. Pide foco de audio temporal sin "duck":
 * la música se pausa mientras habla (bajarla no bastaba para oír la voz con
 * audífonos) y se reanuda al soltar el foco.
 */
class Voz(context: Context) {
    private val principal = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(atributos)
        .setOnAudioFocusChangeListener { }
        .build()
    private var lista = false
    private var pendiente: String? = null
    private var numero = 0L
    private var activa: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { estado ->
        principal.post { alIniciar(estado) }
    }

    /** false si no hay voz en español instalada. */
    var disponible = true
        private set

    private fun alIniciar(estado: Int) {
        if (estado != TextToSpeech.SUCCESS) {
            disponible = false
            return
        }
        val idioma = listOf(Locale("es", "MX"), Locale("es", "US"), Locale("es", "ES"), Locale("es"))
            .firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        if (idioma == null || tts.setLanguage(idioma) < TextToSpeech.LANG_AVAILABLE) {
            disponible = false
            Log.w(TAG, "sin voz en español")
            return
        }
        tts.setAudioAttributes(atributos)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = terminar(utteranceId)
            @Deprecated("Requerido por la API")
            override fun onError(utteranceId: String) = terminar(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = terminar(utteranceId)
        })
        lista = true
        pendiente?.let { decir(it) }
        pendiente = null
    }

    fun decir(texto: String) {
        if (!lista) {
            pendiente = texto
            return
        }
        audio.requestAudioFocus(foco)
        val id = (++numero).toString()
        activa = id
        if (tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) terminar(id)
    }

    private fun terminar(id: String) {
        principal.post {
            if (activa == id) {
                activa = null
                audio.abandonAudioFocusRequest(foco)
            }
        }
    }

    fun cerrar() {
        activa = null
        tts.stop()
        tts.shutdown()
        audio.abandonAudioFocusRequest(foco)
    }

    companion object {
        private const val TAG = "RodadaVoz"
    }
}
