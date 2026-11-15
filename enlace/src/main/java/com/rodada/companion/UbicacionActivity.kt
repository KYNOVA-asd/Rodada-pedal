package com.rodada.companion

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Muestra la última posición que el S40 entregó al Motorola por Bluetooth. */
class UbicacionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refrescar = object : Runnable {
        override fun run() {
            mostrarPunto()
            handler.postDelayed(this, 3_000L)
        }
    }
    private var punto: UbicacionGuardada.Punto? = null
    private var solicitudMs = 0L
    private var horaAnteriorMs = 0L
    private var sinSenal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ubicacion)
        findViewById<Button>(R.id.location_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.location_open_map).setOnClickListener { abrirMapa() }
        findViewById<Button>(R.id.location_refresh).setOnClickListener { solicitar() }
    }

    override fun onResume() {
        super.onResume()
        refrescar.run()
    }

    override fun onPause() {
        handler.removeCallbacks(refrescar)
        super.onPause()
    }

    private fun mostrarPunto() {
        punto = UbicacionGuardada.leer(this)
        val actual = punto
        if (solicitudMs != 0L && actual != null && actual.horaMs > horaAnteriorMs) {
            solicitudMs = 0L
            sinSenal = false
        } else if (solicitudMs != 0L && SystemClock.elapsedRealtime() - solicitudMs > 35_000L) {
            solicitudMs = 0L
            sinSenal = true
        }
        val reciente = actual != null && EnlaceEstado.estado is EnlaceEstado.Estado.Conectado &&
            System.currentTimeMillis() - actual.horaMs in 0..30_000L
        findViewById<TextView>(R.id.location_status).setText(when {
            solicitudMs != 0L -> R.string.location_searching
            sinSenal -> R.string.location_timeout
            actual == null -> R.string.location_empty
            reciente -> R.string.location_live
            else -> R.string.location_saved
        })
        findViewById<TextView>(R.id.location_coordinates).apply {
            visibility = if (actual == null) View.GONE else View.VISIBLE
            if (actual != null) text = getString(R.string.location_coordinates, actual.latitud, actual.longitud)
        }
        findViewById<TextView>(R.id.location_time).apply {
            visibility = if (actual == null) View.GONE else View.VISIBLE
            if (actual != null) {
                val fecha = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale("es", "MX"))
                    .format(Date(actual.horaMs))
                text = getString(R.string.location_time, fecha)
            }
        }
        findViewById<TextView>(R.id.location_accuracy).apply {
            visibility = if (actual == null) View.GONE else View.VISIBLE
            if (actual != null) text = getString(R.string.location_accuracy, actual.precisionMetros.toInt())
        }
        findViewById<TextView>(R.id.location_hint).visibility = if (actual == null) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.location_open_map).isEnabled = actual != null
        findViewById<Button>(R.id.location_refresh).isEnabled = solicitudMs == 0L
    }

    private fun solicitar() {
        if (EnlaceService.actual?.solicitarUbicacion() != true) {
            Toast.makeText(this, R.string.location_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        horaAnteriorMs = punto?.horaMs ?: 0L
        sinSenal = false
        solicitudMs = SystemClock.elapsedRealtime()
        mostrarPunto()
    }

    private fun abrirMapa() {
        val actual = punto ?: return
        val coordenadas = "${actual.latitud},${actual.longitud}"
        val uri = Uri.parse("geo:0,0?q=${Uri.encode("$coordenadas(S40)")}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.location_no_map, Toast.LENGTH_SHORT).show()
        }
    }
}
