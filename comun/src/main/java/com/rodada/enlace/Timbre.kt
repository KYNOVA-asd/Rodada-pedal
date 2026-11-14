package com.rodada.enlace

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * "Buscar mi teléfono": suena fuerte la alarma del sistema y vibra, aunque el
 * teléfono esté en silencio o en vibración (canal de alarma al máximo). Se detiene
 * solo a los [DURACION_MS] o con [detener], y deja el volumen de alarma como estaba.
 */
class Timbre(context: Context) {
    private val contexto = context.applicationContext
    private val audio = contexto.getSystemService(AudioManager::class.java)
    private val vibrador = contexto.getSystemService(Vibrator::class.java)
    private val principal = Handler(Looper.getMainLooper())
    private var tono: Ringtone? = null
    private var volumenAnterior = -1

    /** Se llama al detenerse (por tiempo o a mano), en el hilo principal. */
    var alDetener: (() -> Unit)? = null

    val sonando: Boolean
        get() = tono != null

    private val pararSolo = Runnable { detener() }

    fun sonar() {
        principal.removeCallbacks(pararSolo)
        principal.postDelayed(pararSolo, DURACION_MS)
        if (tono != null) return
        volumenAnterior = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: SecurityException) {
            // Con "No molestar" Android puede no dejar cambiarlo; suena con el volumen que haya.
        }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(contexto, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        tono = RingtoneManager.getRingtone(contexto, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
        if (vibrador.hasVibrator()) {
            vibrador.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
            )
        }
    }

    fun detener() {
        principal.removeCallbacks(pararSolo)
        val estaba = tono ?: return
        estaba.stop()
        tono = null
        vibrador.cancel()
        if (volumenAnterior >= 0) {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, volumenAnterior, 0)
            } catch (_: SecurityException) {
            }
            volumenAnterior = -1
        }
        alDetener?.invoke()
    }

    companion object {
        const val DURACION_MS = 30_000L
    }
}
