package com.rodada.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var estado: TextView
    private lateinit var boton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.state)
        boton = findViewById(R.id.toggle)
        boton.setOnClickListener {
            if (servicioActivo()) {
                stopService(Intent(this, EnlaceService::class.java))
                EnlaceEstado.estado = EnlaceEstado.Estado.Apagado
            } else if (tienePermisos()) {
                activar()
            } else {
                explicarPermisos()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        EnlaceEstado.oyente = ::mostrar
        mostrar(EnlaceEstado.estado)
    }

    override fun onPause() {
        EnlaceEstado.oyente = null
        super.onPause()
    }

    private fun servicioActivo(): Boolean =
        EnlaceEstado.estado is EnlaceEstado.Estado.Esperando || EnlaceEstado.estado is EnlaceEstado.Estado.Conectado

    private fun mostrar(e: EnlaceEstado.Estado) {
        estado.text = when (e) {
            EnlaceEstado.Estado.Apagado -> getString(R.string.state_off)
            EnlaceEstado.Estado.Esperando -> getString(R.string.state_waiting)
            is EnlaceEstado.Estado.Conectado -> getString(R.string.state_connected, e.nombre)
            is EnlaceEstado.Estado.Error -> getString(R.string.state_error, getString(e.mensaje))
        }
        boton.setText(if (servicioActivo()) R.string.stop else R.string.start)
    }

    private fun permisos(): Array<String> =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

    private fun tienePermisos(): Boolean = permisos().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun explicarPermisos() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(R.string.permissions_explanation)
            .setPositiveButton(R.string.permissions_continue) { _, _ -> requestPermissions(permisos(), PERMISOS) }
            .setNegativeButton(R.string.permissions_cancel, null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISOS) return
        if (tienePermisos()) activar() else Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
    }

    private fun activar() {
        startForegroundService(Intent(this, EnlaceService::class.java))
    }

    companion object {
        private const val PERMISOS = 1
    }
}
