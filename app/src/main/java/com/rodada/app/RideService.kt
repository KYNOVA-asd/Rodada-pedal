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

class RideService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var lastAcceptedLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
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
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) {
            RideState.hasFix = false
            RideState.speedKmh = 0f
            lastAcceptedLocation = null
            return
        }

        RideState.hasFix = true
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        RideState.speedKmh = if (speedKmh >= MIN_SPEED_KMH) speedKmh else 0f

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
            lastAcceptedLocation = location
        }
    }

    override fun onProviderDisabled(provider: String) {
        RideState.hasFix = false
        RideState.speedKmh = 0f
        lastAcceptedLocation = null
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        RideState.finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        private const val MIN_SPEED_KMH = 2f
        private const val MIN_DISTANCE_STEP_METERS = 3f
        private const val MAX_BIKE_SPEED_MPS = 25f
        private const val JUMP_TOLERANCE_METERS = 10f
    }
}
