package com.rodada.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val bridgeHandler = Handler(Looper.getMainLooper())
    private val refreshBridge = object : Runnable {
        override fun run() {
            showBridgeState()
            bridgeHandler.postDelayed(this, 1_000L)
        }
    }
    private lateinit var estado: TextView
    private lateinit var boton: Button
    private lateinit var accesoAvisos: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AudioBridgeService.state == AudioBridgeService.State.OFF) {
            AudioBridgeService.restoreVolumeIfNeeded(this)
        }
        setContentView(R.layout.activity_main)
        estado = findViewById(R.id.state)
        boton = findViewById(R.id.toggle)
        boton.setOnClickListener {
            if (servicioActivo()) {
                Ajustes(this).enlaceActivo = false
                stopService(Intent(this, EnlaceService::class.java))
                EnlaceEstado.estado = EnlaceEstado.Estado.Apagado
            } else if (tienePermisos()) {
                activar()
            } else {
                explicarPermisos()
            }
        }
        accesoAvisos = findViewById(R.id.listener_state)
        findViewById<Button>(R.id.listener_access).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(this, AvisosService::class.java).flattenToString()
                    )
            )
        }
        prepararBuscando()
        findViewById<Button>(R.id.finding_banner).setOnClickListener { EnlaceService.actual?.dejarDeSonar() }
        findViewById<Button>(R.id.find_s40).setOnClickListener {
            val enviado = EnlaceService.actual?.buscarS40() ?: false
            Toast.makeText(this, if (enviado) R.string.find_sent else R.string.find_no_link, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.choose_apps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<Button>(R.id.probe_audio).setOnClickListener {
            if (AudioBridgeService.state == AudioBridgeService.State.OFF) startAudioBridge()
            else stopService(Intent(this, AudioBridgeService::class.java))
        }
        if (Ajustes(this).enlaceActivo && tienePermisos() && !servicioActivo()) activar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prepararBuscando()
    }

    /** Abierta por "te están buscando": se ve encima del bloqueo y enciende la pantalla. */
    private fun prepararBuscando() {
        val buscando = intent?.getBooleanExtra(EXTRA_BUSCANDO, false) == true && EnlaceEstado.buscando
        setShowWhenLocked(buscando)
        setTurnScreenOn(buscando)
    }

    private fun mostrarBuscando(buscando: Boolean) {
        findViewById<Button>(R.id.finding_banner).visibility = if (buscando) android.view.View.VISIBLE else android.view.View.GONE
        if (!buscando) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    override fun onResume() {
        super.onResume()
        EnlaceEstado.oyenteBuscando = ::mostrarBuscando
        mostrarBuscando(EnlaceEstado.buscando)
        EnlaceEstado.oyente = ::mostrar
        mostrar(EnlaceEstado.estado)
        val permitido = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, AvisosService::class.java))
        val elegidas = Ajustes(this).apps.size
        accesoAvisos.text = getString(
            if (permitido) R.string.listener_on else R.string.listener_off
        ) + "\n" + resources.getQuantityString(R.plurals.apps_chosen, elegidas, elegidas)
        bridgeHandler.removeCallbacks(refreshBridge)
        refreshBridge.run()
    }

    override fun onPause() {
        bridgeHandler.removeCallbacks(refreshBridge)
        EnlaceEstado.oyenteBuscando = null
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
        if (requestCode == AUDIO_PERMISSION) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
            return
        }
        if (requestCode != PERMISOS) return
        if (tienePermisos()) activar() else Toast.makeText(this, R.string.permissions_needed, Toast.LENGTH_LONG).show()
    }

    private fun activar() {
        Ajustes(this).enlaceActivo = true
        startForegroundService(Intent(this, EnlaceService::class.java))
    }

    private fun startAudioBridge() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
        } else requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()),
            AUDIO_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != AUDIO_PROJECTION || resultCode != RESULT_OK || data == null) return
        startForegroundService(Intent(this, AudioBridgeService::class.java)
            .putExtra(AudioBridgeService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(AudioBridgeService.EXTRA_TOKEN, data))
    }

    private fun showBridgeState() {
        val state = AudioBridgeService.state
        val text = when (state) {
            AudioBridgeService.State.OFF -> R.string.bridge_off
            AudioBridgeService.State.WAITING -> R.string.bridge_waiting
            AudioBridgeService.State.STREAMING -> R.string.bridge_streaming
            AudioBridgeService.State.ERROR -> R.string.bridge_error
        }
        findViewById<TextView>(R.id.probe_result).setText(text)
        findViewById<Button>(R.id.probe_audio).setText(
            if (state == AudioBridgeService.State.OFF) R.string.probe_audio else R.string.bridge_stop
        )
    }

    companion object {
        const val EXTRA_BUSCANDO = "buscando"
        private const val PERMISOS = 1
        private const val AUDIO_PERMISSION = 2
        private const val AUDIO_PROJECTION = 3
    }
}
