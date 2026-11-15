package com.rodada.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rodada.enlace.PuenteAudio
import java.io.IOException

/** Captura solo audio multimedia permitido por Android y lo transmite al S40. */
@SuppressLint("MissingPermission")
class AudioBridgeService : Service() {
    enum class State { OFF, WAITING, STREAMING, ERROR }

    @Volatile private var active = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getParcelableExtra(EXTRA_TOKEN, Intent::class.java)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (token == null || resultCode == 0 || active) {
            if (!active) stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bridge_title), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(R.string.bridge_waiting),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        active = true
        state = State.WAITING
        Thread({ transmit(resultCode, token) }, "RodadaAudioSender").start()
        return START_NOT_STICKY
    }

    private fun notification(message: Int): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(getString(R.string.bridge_title))
        .setContentText(getString(message))
        .setOngoing(true)
        .build()

    private fun transmit(resultCode: Int, token: Intent) {
        try {
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, token)
                ?: error("MediaProjection unavailable")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                ?: error("Bluetooth unavailable")
            server = adapter.listenUsingRfcommWithServiceRecord(PuenteAudio.NOMBRE, PuenteAudio.UUID)
            while (active) {
                val socket = server?.accept() ?: break
                client = socket
                state = State.STREAMING
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(R.string.bridge_streaming))
                try {
                    muteMotorola()
                    stream(socket, p)
                } finally {
                    restoreVolumeIfNeeded(this)
                    try { socket.close() } catch (_: IOException) { }
                    client = null
                }
                if (active) {
                    state = State.WAITING
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(R.string.bridge_waiting))
                }
            }
        } catch (e: Exception) {
            if (active) {
                Log.e("RodadaAudio", "Motorola bridge failed", e)
                state = State.ERROR
            }
        } finally {
            stopSelf()
        }
    }

    private fun stream(socket: BluetoothSocket, p: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val audio = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PuenteAudio.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(PuenteAudio.BUFFER_BYTES)
            .build()
        recorder = audio
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            if (audio.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord")
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, PuenteAudio.SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, PuenteAudio.BITRATE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val output = java.io.DataOutputStream(socket.outputStream)
            output.write(byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte()))
            audio.startRecording()
            val bytes = ByteArray(2_048)
            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L
            var captured = 0L
            var sent = 0L
            var activeSamples = 0L
            var checkpoint = SystemClock.elapsedRealtime()
            fun drain() {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 0)
                    if (index < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val encoded = encoder.getOutputBuffer(index) ?: error("AAC output")
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        if (info.size > 65_535) error("AAC frame too large")
                        output.writeShort(info.size)
                        val frame = ByteArray(info.size)
                        encoded.get(frame)
                        output.write(frame)
                        sent += frame.size + 2L
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
            while (active && socket.isConnected) {
                val count = audio.read(bytes, 0, bytes.size)
                if (count < 0) break
                if (count > 0) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex < 0) { drain(); continue }
                    val input = encoder.getInputBuffer(inputIndex) ?: error("AAC input")
                    input.clear()
                    input.put(bytes, 0, count)
                    encoder.queueInputBuffer(inputIndex, 0, count, ptsUs, 0)
                    ptsUs += (count / 2L) * 1_000_000L / PuenteAudio.SAMPLE_RATE
                    drain()
                    captured += count
                    for (index in 0 until count - 1 step 2) {
                        val sample = ((bytes[index].toInt() and 255) or
                            (bytes[index + 1].toInt() shl 8)).toShort().toInt()
                        if (kotlin.math.abs(sample) > 32) activeSamples++
                    }
                    if (SystemClock.elapsedRealtime() - checkpoint >= 5_000L) {
                        Log.i("RodadaAudio", "Motorola captured=$captured sent=$sent activeSamples=$activeSamples")
                        captured = 0
                        sent = 0
                        activeSamples = 0
                        checkpoint = SystemClock.elapsedRealtime()
                    }
                }
            }
        } finally {
            try { encoder.stop() } catch (_: Exception) { }
            encoder.release()
            try { audio.stop() } catch (_: Exception) { }
            audio.release()
            recorder = null
        }
    }

    override fun onDestroy() {
        active = false
        try { client?.close() } catch (_: IOException) { }
        try { server?.close() } catch (_: IOException) { }
        try { recorder?.stop() } catch (_: Exception) { }
        projection?.stop()
        restoreVolumeIfNeeded(this)
        state = State.OFF
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_TOKEN = "projection_token"
        private const val CHANNEL = "rodada_audio_bridge"
        private const val NOTIFICATION_ID = 42
        private const val VOLUME_PREFS = "rodada_audio_volume"
        private const val ORIGINAL_VOLUME = "original_music_volume"
        private const val ORIGINAL_MUTED = "original_music_muted"
        private const val MUTED_BY_BRIDGE = "muted_by_bridge"
        @Volatile var state = State.OFF
            private set

        fun restoreVolumeIfNeeded(context: android.content.Context) {
            val prefs = context.getSharedPreferences(VOLUME_PREFS, android.content.Context.MODE_PRIVATE)
            val legacyVolume = prefs.contains(ORIGINAL_VOLUME)
            if (!legacyVolume && !prefs.getBoolean(MUTED_BY_BRIDGE, false)) return
            val wasMuted = prefs.getBoolean(ORIGINAL_MUTED, false)
            val manager = context.getSystemService(AudioManager::class.java)
            if (!wasMuted) manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            if (legacyVolume) manager.setStreamVolume(
                AudioManager.STREAM_MUSIC, prefs.getInt(ORIGINAL_VOLUME, 0), 0
            )
            prefs.edit().remove(ORIGINAL_VOLUME).remove(ORIGINAL_MUTED).remove(MUTED_BY_BRIDGE).commit()
            Log.i("RodadaAudio", "Motorola volume restored")
        }
    }

    private fun muteMotorola() {
        val manager = getSystemService(AudioManager::class.java)
        val wasMuted = manager.isStreamMute(AudioManager.STREAM_MUSIC)
        getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE).edit()
            .putBoolean(MUTED_BY_BRIDGE, true).putBoolean(ORIGINAL_MUTED, wasMuted).commit()
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        Log.i("RodadaAudio", "Motorola output muted=${manager.isStreamMute(AudioManager.STREAM_MUSIC)} while streaming")
    }
}
