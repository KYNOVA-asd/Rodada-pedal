package com.rodada.app

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.time.LocalTime

/** Uses only an already installed Spanish voice from the system's default TTS engine. */
class SpeechController(private val activity: Activity) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val speechAudio = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAudio)
        .setOnAudioFocusChangeListener { }
        .build()
    private val tts = TextToSpeech(activity) { status -> handler.post { onInitialized(status) } }
    private var ready = false
    private var closed = false
    private var hasFocus = false
    private var utteranceNumber = 0L
    private var activeUtterance: String? = null

    private fun onInitialized(status: Int) {
        if (closed) return
        if (status != TextToSpeech.SUCCESS) {
            showMissingVoice()
            return
        }
        val installedSpanishVoices = tts.voices.orEmpty()
            .filter { voice ->
                voice.locale.language == "es" &&
                    !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            }
        val selectedVoice = tts.voice
        val spanishVoice = installedSpanishVoices.firstOrNull { voice ->
            voice.name == selectedVoice?.name && voice.locale.country == "US"
        } ?: installedSpanishVoices.sortedBy { voice ->
                when (voice.locale.country) {
                    "MX" -> 0
                    "US" -> 1
                    else -> 2
                }
            }.firstOrNull()
        if (spanishVoice == null || tts.setVoice(spanishVoice) != TextToSpeech.SUCCESS) {
            showMissingVoice()
            return
        }
        tts.setAudioAttributes(speechAudio)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = releaseFocusFor(utteranceId)
            override fun onError(utteranceId: String) = releaseFocusFor(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = releaseFocusFor(utteranceId)
        })
        ready = true
        speak(activity.getString(R.string.speech_ready))
    }

    fun speakStatus(rideActive: Boolean, speedKmh: Int) {
        if (!ready) {
            showMissingVoice()
            return
        }
        val now = LocalTime.now()
        val hour = now.hour % 12
        val hourText = if (hour == 1) {
            activity.getString(R.string.speech_one_oclock)
        } else {
            val words = activity.resources.getStringArray(R.array.speech_hours)
            activity.getString(R.string.speech_other_hour, words[hour])
        }
        val minuteText = when (now.minute) {
            0 -> activity.getString(R.string.speech_on_the_hour)
            15 -> activity.getString(R.string.speech_quarter)
            30 -> activity.getString(R.string.speech_half)
            else -> activity.getString(R.string.speech_minutes, now.minute)
        }
        val message = "$hourText $minuteText." + if (rideActive) {
            " " + activity.getString(R.string.speech_speed, speedKmh)
        } else {
            ""
        }
        speak(message)
    }

    private fun speak(message: String) {
        if (closed) return
        if (!hasFocus && audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Toast.makeText(activity, R.string.speech_audio_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        hasFocus = true
        val utteranceId = (++utteranceNumber).toString()
        activeUtterance = utteranceId
        if (tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId) != TextToSpeech.SUCCESS) {
            releaseFocusFor(utteranceId)
        }
    }

    private fun releaseFocusFor(utteranceId: String) {
        handler.post {
            if (activeUtterance == utteranceId) {
                activeUtterance = null
                abandonFocus()
            }
        }
    }

    private fun abandonFocus() {
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    private fun showMissingVoice() {
        Toast.makeText(activity, R.string.speech_missing_spanish, Toast.LENGTH_LONG).show()
    }

    fun close() {
        closed = true
        activeUtterance = null
        tts.stop()
        tts.shutdown()
        abandonFocus()
    }
}
