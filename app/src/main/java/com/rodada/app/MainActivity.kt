package com.rodada.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
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
    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator
    private lateinit var speech: SpeechController

    private val updateScreen = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        hideSystemBars()
        setContentView(R.layout.activity_main)
        clock = findViewById(R.id.clock)
        speed = findViewById(R.id.speed)
        speedUnit = findViewById(R.id.speed_unit)
        distance = findViewById(R.id.distance)
        elapsed = findViewById(R.id.elapsed)
        audioManager = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        val infoPanel = findViewById<LinearLayout>(R.id.info_panel)
        infoPanel.setOnClickListener {
            speech.speakStatus(RideState.running, RideState.speedKmh.toInt())
        }
        infoPanel.setOnLongClickListener {
            toggleRide()
            true
        }
        findViewById<MusicWheelView>(R.id.music_wheel).onControl = ::handleMusicControl
        speech = SpeechController(this)
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

    override fun onDestroy() {
        speech.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.decorView.windowInsetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
        if (RideState.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun toggleRide() {
        if (RideState.running) {
            stopService(Intent(this, RideService::class.java))
            render()
        } else if (hasRequiredPermissions()) {
            startRide()
        } else {
            explainPermissions()
        }
    }

    private fun handleMusicControl(zone: MusicWheelView.Zone) {
        when (zone) {
            MusicWheelView.Zone.UP ->
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            MusicWheelView.Zone.DOWN ->
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            MusicWheelView.Zone.PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            MusicWheelView.Zone.NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            MusicWheelView.Zone.CENTER -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
        )
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
