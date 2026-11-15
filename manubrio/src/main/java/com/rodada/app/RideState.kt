package com.rodada.app

import android.os.SystemClock

/** Estado de un recorrido; el servicio y la pantalla viven en el mismo proceso. */
internal object RideState {
    var running = false
        private set
    var hasFix = false
    var speedKmh = 0f
    /** Precisión de la última lectura del GPS (aunque se haya descartado); -1 si no hay. */
    var accuracyMeters = -1f
    var distanceMeters = 0f
    /** Velocidad máxima del recorrido (km/h). */
    var maxKmh = 0f
    /** Velocidad mínima mientras se movía (km/h); -1 si aún no se movió. */
    var minMovingKmh = -1f
    /** Tiempo con GPS bueno y velocidad 0 (semáforos, descansos). */
    var stoppedMs = 0L
    /** Veces que pasó de moverse a quedarse en 0 al menos unos segundos. */
    var stops = 0
    /** Según el Motorola llovió hace poco: solo así se avisan los encharcamientos. */
    @Volatile var rainRecent = false
    /** Aviso pendiente para la pantalla: metros al encharcamiento; -1 si no hay. */
    @Volatile var floodAlertMeters = -1
    /** Sube al marcar o borrar puntos, para que el recorrido los vuelva a leer. */
    @Volatile var floodSpotsVersion = 0
    private var startedAtMs = 0L
    private var finalElapsedMs = 0L

    fun begin() {
        running = true
        hasFix = false
        speedKmh = 0f
        accuracyMeters = -1f
        distanceMeters = 0f
        maxKmh = 0f
        minMovingKmh = -1f
        stoppedMs = 0L
        stops = 0
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
