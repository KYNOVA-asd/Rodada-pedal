package com.rodada.app

import android.os.SystemClock

/** Estado de un recorrido; el servicio y la pantalla viven en el mismo proceso. */
internal object RideState {
    var running = false
        private set
    var hasFix = false
    var speedKmh = 0f
    var distanceMeters = 0f
    private var startedAtMs = 0L
    private var finalElapsedMs = 0L

    fun begin() {
        running = true
        hasFix = false
        speedKmh = 0f
        distanceMeters = 0f
        startedAtMs = SystemClock.elapsedRealtime()
        finalElapsedMs = 0L
    }

    fun finish() {
        if (running) finalElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        running = false
        hasFix = false
        speedKmh = 0f
    }

    fun elapsedSeconds(): Long =
        (if (running) SystemClock.elapsedRealtime() - startedAtMs else finalElapsedMs) / 1000L
}
