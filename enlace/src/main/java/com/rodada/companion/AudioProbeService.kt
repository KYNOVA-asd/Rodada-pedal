package com.rodada.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Prueba de viabilidad: mide muestras, sin guardar ni enviar audio. */
class AudioProbeService : Service() {
    enum class Result { IDLE, RUNNING, DETECTED, SILENT, FAILED }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getParcelableExtra(EXTRA_TOKEN, Intent::class.java)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (token == null || resultCode == 0) {
            result = Result.FAILED
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.probe_audio), NotificationManager.IMPORTANCE_LOW))
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.probe_notification))
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        result = Result.RUNNING
        Thread({ probe(resultCode, token) }, "RodadaAudioProbe").start()
        return START_NOT_STICKY
    }

    private fun probe(resultCode: Int, token: Intent) {
        var record: AudioRecord? = null
        val projection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, token)
        } catch (_: Exception) {
            null
        }
        if (projection == null) {
            result = Result.FAILED
            stopSelf()
            return
        }
        projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(4_096)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord")
            record.startRecording()
            val samples = ShortArray(2_048)
            var nonzero = 0L
            val end = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < end) {
                val count = record.read(samples, 0, samples.size)
                if (count < 0) error("AudioRecord.read")
                for (index in 0 until count) if (kotlin.math.abs(samples[index].toInt()) > 32) nonzero++
            }
            result = if (nonzero > 1_000) Result.DETECTED else Result.SILENT
        } catch (_: Exception) {
            result = Result.FAILED
        } finally {
            try { record?.stop() } catch (_: Exception) { }
            record?.release()
            projection.stop()
            stopSelf()
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_TOKEN = "projection_token"
        private const val CHANNEL = "rodada_audio_probe"
        private const val NOTIFICATION_ID = 42
        @Volatile var result = Result.IDLE
    }
}
