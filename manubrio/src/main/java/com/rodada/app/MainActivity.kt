package com.rodada.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
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
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.rodada.enlace.Mensaje
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
    private lateinit var targetStatus: TextView
    private var hidRemote: HidMediaRemote? = null
    private var askedDiscoverable = false
    private var wasConnected = false
    private lateinit var linkStatus: TextView
    private var enlace: EnlaceCliente? = null
    private var afterBluetoothPermission: (() -> Unit)? = null

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
        infoPanel.setOnClickListener { sayStatus() }
        infoPanel.setOnLongClickListener {
            toggleRide()
            true
        }
        findViewById<MusicWheelView>(R.id.music_wheel).onControl = ::handleMusicControl
        speech = SpeechController(this)
        targetStatus = findViewById(R.id.target_status)
        findViewById<android.view.View>(R.id.menu_button).setOnClickListener(::showMenu)
        if (isMotorolaTarget() && hasBluetoothPermissions()) {
            startMotorola()
        } else {
            useThisPhone()
        }
        linkStatus = findViewById(R.id.link_status)
        startLinkIfPossible()
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
        hidRemote?.stop()
        enlace?.detener()
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
        if (isMotorolaTarget()) {
            val remote = hidRemote
            if (remote == null || !remote.isConnected) {
                Toast.makeText(this, R.string.motorola_not_connected, Toast.LENGTH_SHORT).show()
                return
            }
            remote.sendKey(
                when (zone) {
                    MusicWheelView.Zone.UP -> HidMediaRemote.Key.VOLUME_UP
                    MusicWheelView.Zone.DOWN -> HidMediaRemote.Key.VOLUME_DOWN
                    MusicWheelView.Zone.PREVIOUS -> HidMediaRemote.Key.PREVIOUS
                    MusicWheelView.Zone.NEXT -> HidMediaRemote.Key.NEXT
                    MusicWheelView.Zone.CENTER -> HidMediaRemote.Key.PLAY_PAUSE
                }
            )
        } else when (zone) {
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

    private fun showMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add(MENU_TARGET_GROUP, MENU_THIS_PHONE, 0, R.string.target_this_phone)
            menu.add(MENU_TARGET_GROUP, MENU_MOTOROLA, 1, R.string.target_motorola)
            menu.setGroupCheckable(MENU_TARGET_GROUP, true, true)
            menu.findItem(if (isMotorolaTarget()) MENU_MOTOROLA else MENU_THIS_PHONE).isChecked = true
            menu.add(MENU_VOICE_GROUP, MENU_VOICE_PHONE, 2, R.string.voice_this_phone)
            menu.add(MENU_VOICE_GROUP, MENU_VOICE_MOTOROLA, 3, R.string.voice_motorola)
            menu.setGroupCheckable(MENU_VOICE_GROUP, true, true)
            menu.findItem(if (isVoiceOnMotorola()) MENU_VOICE_MOTOROLA else MENU_VOICE_PHONE).isChecked = true
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_THIS_PHONE -> useThisPhone()
                    MENU_MOTOROLA -> onMotorolaChosen()
                    MENU_VOICE_PHONE -> prefs.edit().putString(KEY_VOICE, TARGET_PHONE).apply()
                    MENU_VOICE_MOTOROLA -> {
                        prefs.edit().putString(KEY_VOICE, TARGET_MOTOROLA).apply()
                        withBluetoothPermission { startLinkIfPossible() }
                    }
                }
                true
            }
            show()
        }
        enlace?.reintentar()
    }

    private fun isVoiceOnMotorola(): Boolean = prefs.getString(KEY_VOICE, TARGET_PHONE) == TARGET_MOTOROLA

    /** Con "Voz en: Motorola" y enlace activo, lo dice el Motorola; si no, este teléfono. */
    private fun sayStatus() {
        val link = enlace
        if (isVoiceOnMotorola() && link != null && link.conectado) {
            val text = speech.statusMessage(RideState.running, RideState.speedKmh.toInt())
            link.enviar(Mensaje.Decir(text))
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            speech.speakStatus(RideState.running, RideState.speedKmh.toInt())
        }
    }

    /** El enlace se abre si ya hay un Motorola emparejado (lo guarda el control HID) y permiso. */
    private fun startLinkIfPossible() {
        val address = prefs.getString(KEY_MOTOROLA_ADDRESS, null)
        if (enlace != null || address == null || !hasBluetoothPermissions()) {
            showLinkStatus()
            return
        }
        val link = EnlaceCliente(this, address, { showLinkStatus() }) { mensaje ->
            when (mensaje) {
                is Mensaje.Hola, is Mensaje.Decir, is Mensaje.Notif -> Unit
            }
        }
        enlace = link
        link.iniciar()
        showLinkStatus()
    }

    private fun showLinkStatus() {
        val link = enlace
        if (link == null) {
            linkStatus.text = ""
            return
        }
        when (link.estado) {
            EnlaceCliente.Estado.CONECTADO -> {
                linkStatus.setText(R.string.link_connected)
                linkStatus.setTextColor(COLOR_OK)
            }
            EnlaceCliente.Estado.CONECTANDO -> {
                linkStatus.setText(R.string.link_connecting)
                linkStatus.setTextColor(COLOR_WARNING)
            }
            EnlaceCliente.Estado.DESCONECTADO -> {
                linkStatus.setText(R.string.link_disconnected)
                linkStatus.setTextColor(COLOR_OFF)
            }
        }
    }

    private fun withBluetoothPermission(action: () -> Unit) {
        if (hasBluetoothPermissions()) {
            action()
            return
        }
        afterBluetoothPermission = action
        AlertDialog.Builder(this)
            .setTitle(R.string.hid_permissions_title)
            .setMessage(R.string.hid_permissions_explanation)
            .setPositiveButton(R.string.permissions_continue) { _, _ ->
                requestPermissions(BLUETOOTH_PERMISSIONS, BLUETOOTH_PERMISSIONS_REQUEST)
            }
            .setNegativeButton(R.string.permissions_cancel) { _, _ -> afterBluetoothPermission = null }
            .show()
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private fun isMotorolaTarget(): Boolean = prefs.getString(KEY_TARGET, TARGET_PHONE) == TARGET_MOTOROLA

    /** La rueda controla este teléfono; se suelta el HID para no gastar Bluetooth. */
    private fun useThisPhone() {
        prefs.edit().putString(KEY_TARGET, TARGET_PHONE).apply()
        hidRemote?.stop()
        hidRemote = null
        wasConnected = false
        targetStatus.setText(R.string.target_status_phone)
        targetStatus.setTextColor(COLOR_NORMAL)
    }

    private fun hasBluetoothPermissions(): Boolean = BLUETOOTH_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onMotorolaChosen() {
        if (hasBluetoothPermissions()) {
            startMotorola()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hid_permissions_title)
            .setMessage(R.string.hid_permissions_explanation)
            .setPositiveButton(R.string.permissions_continue) { _, _ ->
                requestPermissions(BLUETOOTH_PERMISSIONS, BLUETOOTH_PERMISSIONS_REQUEST)
            }
            .setNegativeButton(R.string.permissions_cancel, null)
            .show()
    }

    /** Registra el HID solo mientras el destino es el Motorola. */
    private fun startMotorola() {
        prefs.edit().putString(KEY_TARGET, TARGET_MOTOROLA).apply()
        if (hidRemote != null) return
        val remote = HidMediaRemote(this, prefs.getString(KEY_MOTOROLA_ADDRESS, null), ::onHidStatus)
        hidRemote = remote
        askedDiscoverable = false
        wasConnected = false
        showTargetStatus(remote.status)
        remote.start()
    }

    private fun onHidStatus(status: HidMediaRemote.Status) {
        if (hidRemote == null) return
        status.connectedAddress?.let { address ->
            prefs.edit().putString(KEY_MOTOROLA_ADDRESS, address).apply()
            startLinkIfPossible()
        }
        val connected = status.connectedName != null
        if (connected && !wasConnected) {
            Toast.makeText(this, R.string.motorola_connected_toast, Toast.LENGTH_SHORT).show()
        } else if (!connected && wasConnected) {
            Toast.makeText(this, R.string.motorola_disconnected_toast, Toast.LENGTH_LONG).show()
        }
        wasConnected = connected
        showTargetStatus(status)
        // Primera vez (sin Motorola emparejado): hacer visible el S40 ya registrado como HID.
        if (status.registered && !connected && !status.reconnecting && !askedDiscoverable &&
            prefs.getString(KEY_MOTOROLA_ADDRESS, null) == null
        ) {
            askedDiscoverable = true
            Toast.makeText(this, R.string.motorola_pair_hint, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        }
    }

    private fun showTargetStatus(status: HidMediaRemote.Status) {
        val error = when (status.lastError) {
            HidMediaRemote.Error.BLUETOOTH_OFF -> R.string.hid_error_bluetooth_off
            HidMediaRemote.Error.PROFILE_UNAVAILABLE, HidMediaRemote.Error.REGISTER_FAILED ->
                R.string.hid_error_register
            HidMediaRemote.Error.SEND_FAILED -> R.string.hid_error_send
            null -> null
        }
        when {
            status.connectedName != null -> {
                targetStatus.setText(R.string.target_status_motorola_connected)
                targetStatus.setTextColor(COLOR_OK)
            }
            error != null -> {
                targetStatus.text = getString(R.string.target_status_motorola_error, getString(error))
                targetStatus.setTextColor(COLOR_ERROR)
            }
            status.reconnecting -> {
                targetStatus.setText(R.string.target_status_motorola_reconnecting)
                targetStatus.setTextColor(COLOR_WARNING)
            }
            else -> {
                targetStatus.setText(R.string.target_status_motorola_waiting)
                targetStatus.setTextColor(COLOR_WARNING)
            }
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
        if (requestCode == BLUETOOTH_PERMISSIONS_REQUEST) {
            val action = afterBluetoothPermission ?: ::startMotorola
            afterBluetoothPermission = null
            if (hasBluetoothPermissions()) {
                action()
                startLinkIfPossible()
            } else {
                Toast.makeText(this, R.string.hid_permissions_needed, Toast.LENGTH_LONG).show()
            }
            return
        }
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
        private const val BLUETOOTH_PERMISSIONS_REQUEST = 101
        private const val PREFS = "rodada"
        private const val KEY_TARGET = "destino_rueda"
        private const val KEY_MOTOROLA_ADDRESS = "motorola_direccion"
        private const val TARGET_PHONE = "este_telefono"
        private const val TARGET_MOTOROLA = "motorola"
        private const val MENU_TARGET_GROUP = 1
        private const val MENU_THIS_PHONE = 1
        private const val MENU_MOTOROLA = 2
        private const val MENU_VOICE_GROUP = 2
        private const val MENU_VOICE_PHONE = 3
        private const val MENU_VOICE_MOTOROLA = 4
        private const val KEY_VOICE = "voz_en"
        private const val COLOR_OFF = 0xFF9E9E9E.toInt()
        private const val COLOR_NORMAL = 0xFFFFFFFF.toInt()
        private const val COLOR_OK = 0xFF4CAF50.toInt()
        private const val COLOR_WARNING = 0xFFFFC107.toInt()
        private const val COLOR_ERROR = 0xFFFF5252.toInt()
        private val BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }
}
