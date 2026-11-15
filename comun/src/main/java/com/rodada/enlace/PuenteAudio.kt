package com.rodada.enlace

import java.util.UUID

/** Canal Bluetooth independiente del enlace de órdenes y notificaciones. */
object PuenteAudio {
    val UUID: UUID = java.util.UUID.fromString("9ec17869-5880-4a0b-a273-55041bfbb170")
    const val NOMBRE = "Rodada Audio"
    const val SAMPLE_RATE = 24_000
    const val BITRATE = 64_000
    const val BUFFER_BYTES = 8_192
    val AAC_CONFIG = byteArrayOf(0x13, 0x08) // AAC-LC, 24 kHz, mono
}
