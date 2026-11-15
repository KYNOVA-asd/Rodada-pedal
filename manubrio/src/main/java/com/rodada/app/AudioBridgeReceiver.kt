package com.rodada.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.rodada.enlace.PuenteAudio
import java.io.IOException
import java.io.DataInputStream
import java.nio.ByteBuffer

/** Reproduce por la bocina o los audífonos del S40 el audio capturado en el Motorola. */
@SuppressLint("MissingPermission")
class AudioBridgeReceiver : Service() {
    enum class State { OFF, CONNECTING, PLAYING, ERROR }

    @Volatile private var active = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var track: AudioTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (address.isNullOrBlank() || active) {
            if (!active) stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.audio_bridge_title), NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification(R.string.audio_connecting),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        active = true
        current = this
        state = State.CONNECTING
        Thread({ receive(address) }, "RodadaAudioReceiver").start()
        return START_NOT_STICKY
    }

    private fun notification(message: Int): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(getString(R.string.audio_bridge_title))
        .setContentText(getString(message))
        .setOngoing(true)
        .build()

    private fun receive(address: String) {
        var decoder: MediaCodec? = null
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
            val device = adapter.bondedDevices.firstOrNull { it.address == address } ?: error("Motorola not paired")
            val connection = device.createRfcommSocketToServiceRecord(PuenteAudio.UUID)
            socket = connection
            connection.connect()
            val input = DataInputStream(connection.inputStream)
            val magic = ByteArray(4)
            input.readFully(magic)
            if (!magic.contentEquals(byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte())))
                error("Invalid audio stream")
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder = codec
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, PuenteAudio.SAMPLE_RATE, 1)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(PuenteAudio.AAC_CONFIG))
            codec.configure(format, null, null, 0)
            codec.start()
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PuenteAudio.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(PuenteAudio.BUFFER_BYTES)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = player
            if (player.state != AudioTrack.STATE_INITIALIZED) error("AudioTrack")
            getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { player.preferredDevice = it }
            player.play()
            state = State.PLAYING
            MusicaS40Service.stopRadioForAudioBridge()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(R.string.audio_playing))
            val info = MediaCodec.BufferInfo()
            var received = 0L
            var decoded = 0L
            var activeSamples = 0L
            var checkpoint = SystemClock.elapsedRealtime()
            fun drain() {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index < 0) break
                    if (info.size > 0) {
                        val pcm = codec.getOutputBuffer(index) ?: error("AAC output")
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        decoded += info.size
                        val copy = pcm.duplicate()
                        while (copy.remaining() >= 2) {
                            val sample = copy.short.toInt()
                            if (kotlin.math.abs(sample) > 32) activeSamples++
                        }
                        while (pcm.hasRemaining() && active) {
                            val written = player.write(pcm, pcm.remaining(), AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) break
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
            while (active) {
                val length = input.readUnsignedShort()
                if (length == 0 || length > 4_096) error("Invalid AAC frame")
                val frame = ByteArray(length)
                input.readFully(frame)
                received += length + 2L
                var index = codec.dequeueInputBuffer(10_000)
                if (index < 0) { drain(); index = codec.dequeueInputBuffer(10_000) }
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: error("AAC input")
                    buffer.clear()
                    buffer.put(frame)
                    codec.queueInputBuffer(index, 0, length, 0, 0)
                }
                drain()
                if (SystemClock.elapsedRealtime() - checkpoint >= 5_000L) {
                    Log.i("RodadaAudio", "S40 received=$received decoded=$decoded activeSamples=$activeSamples route=${player.routedDevice?.type}")
                    received = 0
                    decoded = 0
                    activeSamples = 0
                    checkpoint = SystemClock.elapsedRealtime()
                }
            }
        } catch (e: Exception) {
            if (active) {
                Log.e("RodadaAudio", "S40 bridge failed", e)
                state = State.ERROR
            }
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            decoder?.release()
            try { track?.stop() } catch (_: Exception) { }
            track?.release()
            track = null
            stopSelf()
        }
    }

    override fun onDestroy() {
        active = false
        try { socket?.close() } catch (_: IOException) { }
        if (current === this) current = null
        state = State.OFF
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ADDRESS = "motorola_address"
        private const val CHANNEL = "rodada_audio_receiver"
        private const val NOTIFICATION_ID = 43
        @Volatile var current: AudioBridgeReceiver? = null
            private set
        @Volatile var state = State.OFF
            private set
    }
}
