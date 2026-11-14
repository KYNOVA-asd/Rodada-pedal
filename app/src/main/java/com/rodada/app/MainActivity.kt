package com.rodada.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private lateinit var clock: TextView
    private lateinit var speed: TextView
    private lateinit var speedUnit: TextView
    private lateinit var distance: TextView
    private lateinit var elapsed: TextView
    private lateinit var rideButton: Button

    private val updateScreen = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.navigationBarColor = Color.BLACK
        setContentView(R.layout.activity_main)
        clock = findViewById(R.id.clock)
        speed = findViewById(R.id.speed)
        speedUnit = findViewById(R.id.speed_unit)
        distance = findViewById(R.id.distance)
        elapsed = findViewById(R.id.elapsed)
        rideButton = findViewById(R.id.ride_button)
        rideButton.setOnClickListener {
            if (RideState.running) {
                stopService(Intent(this, RideService::class.java))
                render()
            } else if (hasRequiredPermissions()) {
                startRide()
            } else {
                explainPermissions()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(updateScreen)
        updateScreen.run()
    }

    override fun onPause() {
        handler.removeCallbacks(updateScreen)
        super.onPause()
    }

    private fun render() {
        clock.text = LocalTime.now().format(timeFormat)
        if (RideState.running && !RideState.hasFix) {
            speed.text = getString(R.string.searching_gps)
            speedUnit.text = ""
        } else {
            speed.text = getString(R.string.speed_value, RideState.speedKmh.toInt())
            speedUnit.setText(R.string.speed_unit)
        }
        distance.text = getString(R.string.distance_value, RideState.distanceMeters / 1000f)
        val seconds = RideState.elapsedSeconds()
        elapsed.text = getString(R.string.elapsed_value, seconds / 3600, (seconds / 60) % 60, seconds % 60)
        rideButton.setText(if (RideState.running) R.string.stop_ride else R.string.start_ride)
        if (RideState.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun explainPermissions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(R.string.permissions_explanation)
            .setPositiveButton(R.string.permissions_continue) { _, _ ->
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    PERMISSIONS_REQUEST
                )
            }
            .setNegativeButton(R.string.permissions_cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST) return
        if (hasRequiredPermissions()) {
            startRide()
        } else {
            Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
        }
    }

    private fun startRide() {
        val locationManager = getSystemService(LocationManager::class.java)
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, R.string.location_off, Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, RideService::class.java))
    }

    companion object {
        private const val PERMISSIONS_REQUEST = 100
    }
}
