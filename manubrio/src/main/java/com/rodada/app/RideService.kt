package com.rodada.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

class RideService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var lastAcceptedLocation: Location? = null
    private var lastGoodLocation: Location? = null
    private lateinit var odometer: OdometerStore
    private var pendingMeters = 0.0
    private var lastSaveMs = 0L
    /** Hora de la última lectura buena, para sumar el tiempo detenido. */
    private var lastFixMs = 0L
    /** Para contar parones: ya se movió, desde cuándo está en 0 y si ese parón ya contó. */
    private var hasMoved = false
    private var stopStartedMs = 0L
    private var stopCounted = false
    /** Encharcamientos del usuario y cuáles ya se avisaron en este recorrido. */
    private var floodSpots: List<Pair<Double, Double>> = emptyList()
    private var floodVersionLoaded = -1
    private val floodsWarned = mutableSetOf<Int>()
    /** Velocidades de los últimos segundos, para detectar un frenón (posible caída). */
    private val recentSpeeds = ArrayDeque<Pair<Long, Float>>()
    private var lastBrakeMs = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        odometer = OdometerStore(this)
        lastSaveMs = SystemClock.elapsedRealtime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (RideState.running) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            createNotificationChannel()
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            RideState.begin()
            Satelites.empezar(locationManager, QUIEN)
            EnlaceCliente.enviarSiConectado(com.rodada.enlace.Mensaje.Recorrido(true))
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalArgumentException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        RideState.accuracyMeters = if (location.hasAccuracy()) location.accuracy else -1f
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) {
            RideState.hasFix = false
            RideState.speedKmh = 0f
            lastAcceptedLocation = null
            return
        }

        RideState.hasFix = true
        lastGoodLocation = location
        UbicacionS40.actualizar(this, location)
        checkFloods(location)
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        RideState.speedKmh = if (speedKmh >= MIN_SPEED_KMH) speedKmh else 0f
        val now = SystemClock.elapsedRealtime()
        if (lastFixMs > 0L && RideState.speedKmh == 0f) {
            // Tope por si el GPS se perdió un rato: no cuenta un hueco largo como detenido.
            RideState.stoppedMs += (now - lastFixMs).coerceAtMost(MAX_STOP_STEP_MS)
        }
        lastFixMs = now
        countStops(now)
        checkHardBrake(now, speedKmh)
        if (RideState.speedKmh > 0f) {
            if (RideState.speedKmh > RideState.maxKmh) RideState.maxKmh = RideState.speedKmh
            if (RideState.minMovingKmh < 0f || RideState.speedKmh < RideState.minMovingKmh) {
                RideState.minMovingKmh = RideState.speedKmh
            }
        }

        if (RideState.speedKmh == 0f) {
            lastAcceptedLocation = location
            return
        }

        val previous = lastAcceptedLocation
        if (previous == null) {
            lastAcceptedLocation = location
            return
        }

        val seconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000f
        if (seconds <= 0f) return
        val meters = previous.distanceTo(location)
        if (meters > seconds * MAX_BIKE_SPEED_MPS + JUMP_TOLERANCE_METERS) {
            lastAcceptedLocation = location
        } else if (meters >= MIN_DISTANCE_STEP_METERS) {
            RideState.distanceMeters += meters
            pendingMeters += meters.toDouble()
            if (SystemClock.elapsedRealtime() - lastSaveMs >= SAVE_INTERVAL_MS) saveDistance()
            lastAcceptedLocation = location
        }
    }

    /** Si llovió y hay un encharcamiento a menos de [FLOOD_WARN_METERS], avisa una vez. */
    private fun checkFloods(location: Location) {
        if (floodVersionLoaded != RideState.floodSpotsVersion) {
            floodVersionLoaded = RideState.floodSpotsVersion
            floodSpots = odometer.floodSpots()
            floodsWarned.clear()
        }
        if (!RideState.rainRecent || floodSpots.isEmpty()) return
        val result = FloatArray(1)
        floodSpots.forEachIndexed { index, (lat, lon) ->
            if (index in floodsWarned) return@forEachIndexed
            Location.distanceBetween(location.latitude, location.longitude, lat, lon, result)
            if (result[0] <= FLOOD_WARN_METERS) {
                floodsWarned.add(index)
                RideState.floodAlertMeters = result[0].toInt()
            }
        }
    }

    /** Un parón cuenta al llevar [STOP_MIN_MS] en 0 después de haberse movido. */
    private fun countStops(now: Long) {
        if (RideState.speedKmh > 0f) {
            hasMoved = true
            stopStartedMs = 0L
            stopCounted = false
            return
        }
        if (!hasMoved) return
        if (stopStartedMs == 0L) stopStartedMs = now
        if (!stopCounted && now - stopStartedMs >= STOP_MIN_MS) {
            RideState.stops++
            stopCounted = true
        }
    }

    /**
     * Frenón: en los últimos segundos iba a [BRAKE_FROM_KMH] o más y ahora va casi
     * parado. Solo avisa al Motorola; él decide si fue caída (golpe + quietud).
     */
    private fun checkHardBrake(now: Long, rawKmh: Float) {
        while (recentSpeeds.isNotEmpty() && now - recentSpeeds.first().first > BRAKE_WINDOW_MS) recentSpeeds.removeFirst()
        val wasFast = recentSpeeds.any { it.second >= BRAKE_FROM_KMH }
        recentSpeeds.addLast(now to rawKmh)
        if (wasFast && rawKmh < BRAKE_TO_KMH && now - lastBrakeMs > BRAKE_REPEAT_MS) {
            lastBrakeMs = now
            recentSpeeds.clear()
            EnlaceCliente.enviarSiConectado(com.rodada.enlace.Mensaje.Frenon)
        }
    }

    override fun onProviderDisabled(provider: String) {
        RideState.hasFix = false
        RideState.speedKmh = 0f
        lastAcceptedLocation = null
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        Satelites.terminar(QUIEN)
        EnlaceCliente.enviarSiConectado(com.rodada.enlace.Mensaje.Recorrido(false))
        lastGoodLocation?.let { UbicacionS40.actualizar(this, it, forzar = true) }
        saveDistance()
        if (RideState.running && lastFixMs > 0L) {
            odometer.saveRide(
                RideState.elapsedSeconds(), RideState.distanceMeters.toDouble(),
                RideState.maxKmh, RideState.minMovingKmh, RideState.stoppedMs / 1000L, RideState.stops
            )
        }
        odometer.close()
        RideState.finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveDistance() {
        if (pendingMeters > 0.0) {
            odometer.addMeters(pendingMeters)
            pendingMeters = 0.0
        }
        lastSaveMs = SystemClock.elapsedRealtime()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(getString(R.string.ride_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rodada_recorrido"
        private const val NOTIFICATION_ID = 1
        private const val MAX_ACCURACY_METERS = 25f
        private const val MAX_STOP_STEP_MS = 5_000L
        private const val STOP_MIN_MS = 5_000L
        private const val FLOOD_WARN_METERS = 300f
        private const val BRAKE_WINDOW_MS = 4_000L
        private const val BRAKE_FROM_KMH = 15f
        private const val BRAKE_TO_KMH = 3f
        private const val BRAKE_REPEAT_MS = 15_000L
        private const val QUIEN = "recorrido"
        private const val MIN_SPEED_KMH = 2f
        private const val MIN_DISTANCE_STEP_METERS = 3f
        private const val MAX_BIKE_SPEED_MPS = 25f
        private const val JUMP_TOLERANCE_METERS = 10f
        private const val SAVE_INTERVAL_MS = 10_000L
    }
}
