package com.rodada.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private lateinit var clock: TextView

    private val updateClock = object : Runnable {
        override fun run() {
            clock.text = LocalTime.now().format(timeFormat)
            val millisUntilNextMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
            handler.postDelayed(this, millisUntilNextMinute)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.navigationBarColor = Color.BLACK
        setContentView(R.layout.activity_main)
        clock = findViewById(R.id.clock)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(updateClock)
        updateClock.run()
    }

    override fun onPause() {
        handler.removeCallbacks(updateClock)
        super.onPause()
    }
}
