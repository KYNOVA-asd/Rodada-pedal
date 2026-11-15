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
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.rodada.enlace.Mensaje
import com.rodada.enlace.SesionMusica
import com.rodada.enlace.Timbre

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speed: LedTextView
    private lateinit var speedUnit: LedTextView
    private lateinit var distance: LedTextView
    private lateinit var elapsed: LedTextView
    private val sessionStartedAtMs = SystemClock.elapsedRealtime()
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
    private lateinit var notice: LinearLayout
    /** Últimas notificaciones, la más nueva primero. Solo en memoria; nunca en disco. */
    private val notices = ArrayDeque<Mensaje.Notif>()
    private var noticeIndex = 0
    /** Cuántas de las más nuevas no se han escuchado todavía. */
    private var unread = 0
    private lateinit var unreadBadge: LedTextView
    private lateinit var ticker: LedTickerView
    /** Lo último que mandó el Motorola por el enlace. */
    private var linkSong: Mensaje.Cancion? = null
    /** Última instrucción de navegación, para vibrar solo cuando cambia la maniobra. */
    private var lastManeuver: String? = null
    private val dimScreen = Runnable { if (currentCall == null || callActive) setBrightness(DIM_BRIGHTNESS) }
    private var lcdBacklit: Boolean? = null
    private var wakeOnUp = false
    /** Lo que suena en este S40 (solo con destino "este teléfono" y acceso concedido). */
    private var localMusic: SesionMusica? = null
    /** Páginas de la pantallita: MAPA ⟵ CENTRO ⟶ NOTIFICACIONES. */
    private enum class Page { MAP, CENTER, NOTICES }
    private var page = Page.CENTER
    /** Vuelta automática al centro tras un salto automático o un aviso. */
    private val hideNotice = Runnable { showPage(Page.CENTER) }

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
        applyOrientation(resources.configuration)
        speed = findViewById(R.id.speed)
        speedUnit = findViewById(R.id.speed_unit)
        distance = findViewById(R.id.distance)
        elapsed = findViewById(R.id.elapsed)
        audioManager = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        val infoPanel = findViewById<LinearLayout>(R.id.info_panel)
        // Un toque: hora. Dos toques: última notificación. Mantener: iniciar o detener recorrido.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    currentCall != null && !callActive -> vibrator.cancel()
                    page == Page.MAP -> openMap()
                    page == Page.NOTICES -> readShownNotice()
                    unread > 0 -> {
                        // Mensajes sin escuchar tienen prioridad sobre la hora.
                        noticeIndex = unread - 1
                        displayNotice(noticeIndex)
                        readShownNotice()
                    }
                    else -> sayStatus()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                sayLastNotice()
                return true
            }
            override fun onLongPress(e: MotionEvent) = toggleRide()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                val minPx = SWIPE_MIN_PX * resources.displayMetrics.density
                if (-dy > minPx && -dy > kotlin.math.abs(dx)) {
                    // Deslizar hacia arriba: quitar el aviso y marcar todo como leído.
                    dismissNotices()
                    return true
                }
                if (kotlin.math.abs(dx) < minPx ||
                    kotlin.math.abs(dx) < kotlin.math.abs(dy)
                ) return false
                // Dedo de izquierda a derecha (dx > 0): mapa. De derecha a izquierda (dx < 0):
                // notificaciones; seguir en ese sentido pasa a una más vieja. En sentido
                // contrario se vuelve al centro.
                when {
                    dx > 0 && page == Page.CENTER -> showPage(Page.MAP)
                    dx > 0 && page == Page.NOTICES -> showPage(Page.CENTER)
                    dx < 0 && page == Page.MAP -> showPage(Page.CENTER)
                    dx < 0 -> browseNotices()
                }
                return true
            }
        })
        val touch = android.view.View.OnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            gestures.onTouchEvent(event)
        }
        infoPanel.setOnTouchListener(touch)
        findViewById<MusicWheelView>(R.id.music_wheel).onControl = ::handleMusicControl
        speech = SpeechController(this)
        targetStatus = findViewById(R.id.target_status)
        ticker = findViewById(R.id.ticker)
        MusicaS40Service.onRadioChanged = { updateTicker() }
        ticker.setOnClickListener { onTickerTapped() }
        findViewById<android.view.View>(R.id.menu_button).setOnClickListener(::showMenu)
        if (isMotorolaTarget() && hasBluetoothPermissions()) {
            startMotorola()
        } else {
            useThisPhone()
        }
        notice = findViewById(R.id.notice)
        notice.setOnTouchListener(touch)
        unreadBadge = findViewById(R.id.unread_badge)
        linkStatus = findViewById(R.id.link_status)
        startLinkIfPossible()
        updateTicker()
        setLcdBacklight(false)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(updateScreen)
        updateScreen.run()
        // Al volver de Ajustes con el acceso recién concedido.
        if (!isMotorolaTarget() && localMusic == null) startLocalMusic()
        ticker.reanudar()
        wakeScreen()
    }

    override fun onPause() {
        handler.removeCallbacks(updateScreen)
        // Pantalla apagada o app en segundo plano: la franja deja de animar.
        ticker.pausar()
        handler.removeCallbacks(dimScreen)
        super.onPause()
    }

    override fun onDestroy() {
        MusicaS40Service.onRadioChanged = null
        speech.close()
        hidRemote?.stop()
        enlace?.detener()
        localMusic?.detener()
        if (currentCall != null) vibrator.cancel()
        finder.detener()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    /**
     * Vertical: pantallita arriba (55 %) y rueda abajo. De lado: pantallita a la
     * izquierda (60 %) y rueda a la derecha. Mismo árbol de vistas, sin recrear nada.
     */
    private fun applyOrientation(config: android.content.res.Configuration) {
        val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = findViewById<LinearLayout>(R.id.root)
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val density = resources.displayMetrics.density
        fun half(id: Int, weight: Float) {
            val view = findViewById<android.view.View>(id)
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.weight = weight
            if (landscape) {
                params.width = 0
                params.height = LinearLayout.LayoutParams.MATCH_PARENT
            } else {
                params.width = LinearLayout.LayoutParams.MATCH_PARENT
                params.height = 0
            }
            view.layoutParams = params
        }
        half(R.id.screen_half, if (landscape) 0.6f else 0.58f)
        half(R.id.wheel_half, if (landscape) 0.4f else 0.42f)
        // De lado, la pantallita deja 6 dp a la derecha en vez de abajo.
        val screen = findViewById<android.view.View>(R.id.screen_half)
        (screen.layoutParams as LinearLayout.LayoutParams).setMargins(
            (12 * density).toInt(), (12 * density).toInt(),
            ((if (landscape) 6 else 12) * density).toInt(),
            ((if (landscape) 12 else 6) * density).toInt()
        )
        screen.requestLayout()
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
        if (RideState.running && !RideState.hasFix) {
            speed.text = getString(R.string.searching_gps)
            speedUnit.text = ""
        } else {
            speed.text = getString(R.string.speed_value, RideState.speedKmh.toInt())
            speedUnit.setText(R.string.speed_unit)
        }
        distance.text = getString(R.string.distance_value, RideState.distanceMeters / 1000f)
        updateNavSummary()
        val seconds = (SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1000L
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
        // Llamada sonando: derecha contesta, izquierda rechaza, centro calla la vibración.
        // Llamada en curso: izquierda cuelga, centro altavoz, derecha silenciar. + y − son volumen.
        if (currentCall != null) {
            val action = when (zone) {
                MusicWheelView.Zone.NEXT ->
                    if (callActive) Mensaje.Responder.Accion.SILENCIAR else Mensaje.Responder.Accion.CONTESTAR
                MusicWheelView.Zone.PREVIOUS ->
                    if (callActive) Mensaje.Responder.Accion.COLGAR else Mensaje.Responder.Accion.RECHAZAR
                MusicWheelView.Zone.CENTER -> if (callActive) Mensaje.Responder.Accion.ALTAVOZ else null
                else -> null
            }
            when {
                action != null -> { callAction(action); return }
                zone == MusicWheelView.Zone.CENTER || zone == MusicWheelView.Zone.NEXT -> {
                    vibrator.cancel()
                    return
                }
            }
        }
        if (!isMotorolaTarget() && MusicaS40Service.currentStation != null &&
            MusicaS40Service.sendRadioAction(zone)
        ) {
            if (vibrator.hasVibrator()) vibrator.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
            return
        }
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
        if (zone == MusicWheelView.Zone.UP || zone == MusicWheelView.Zone.DOWN) {
            ticker.mostrarUnaVez(getString(
                if (zone == MusicWheelView.Zone.UP) R.string.volume_up_feedback else R.string.volume_down_feedback
            ))
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
        val content = layoutInflater.inflate(R.layout.dialog_mode, null)
        val dialog = AlertDialog.Builder(this).setView(content).create()
        fun choice(id: Int, selected: Boolean, label: Int, action: () -> Unit) {
            content.findViewById<TextView>(id).apply {
                text = getString(if (selected) R.string.mode_selected else R.string.mode_unselected, getString(label))
                setOnClickListener { dialog.dismiss(); action() }
            }
        }
        choice(R.id.mode_wheel_phone, !isMotorolaTarget(), R.string.target_this_phone, ::useThisPhone)
        choice(R.id.mode_wheel_motorola, isMotorolaTarget(), R.string.target_motorola, ::onMotorolaChosen)
        choice(R.id.mode_voice_phone, !isVoiceOnMotorola(), R.string.voice_this_phone) {
            prefs.edit().putString(KEY_VOICE, TARGET_PHONE).apply()
        }
        choice(R.id.mode_voice_motorola, isVoiceOnMotorola(), R.string.voice_motorola) {
            prefs.edit().putString(KEY_VOICE, TARGET_MOTOROLA).apply()
            withBluetoothPermission { startLinkIfPossible() }
        }
        OdometerStore(this).use { store ->
            content.findViewById<TextView>(R.id.mode_odometer).text =
                getString(R.string.mode_odometer_value, store.totalMeters() / 1000.0)
        }
        content.findViewById<TextView>(R.id.mode_reset).setOnClickListener {
            if (RideState.running) {
                Toast.makeText(this, R.string.mode_odometer_reset_running, Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.mode_odometer_reset_title)
                    .setMessage(R.string.mode_odometer_reset_message)
                    .setNegativeButton(R.string.permissions_cancel, null)
                    .setPositiveButton(R.string.mode_odometer_reset) { _, _ ->
                        OdometerStore(this).use { it.reset() }
                        content.findViewById<TextView>(R.id.mode_odometer).text =
                            getString(R.string.mode_odometer_value, 0.0)
                    }
                    .show()
            }
        }
        content.findViewById<TextView>(R.id.mode_find).apply {
            setText(if (findingMotorola) R.string.menu_find_stop else R.string.menu_find)
            setOnClickListener { dialog.dismiss(); toggleFindMotorola() }
        }
        content.findViewById<TextView>(R.id.mode_close).setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(R.color.watch_case)
                setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        dialog.show()
        enlace?.reintentar()
    }

    private fun isVoiceOnMotorola(): Boolean = prefs.getString(KEY_VOICE, TARGET_PHONE) == TARGET_MOTOROLA

    /** Deslizar a la derecha: la última; cada deslizamiento más, una anterior (y vuelve a empezar). */
    private fun browseNotices() {
        if (notices.isEmpty()) {
            findViewById<LedTextView>(R.id.notice_app).text = ""
            findViewById<LedTextView>(R.id.notice_title).setText(R.string.no_notifications)
            findViewById<LedTextView>(R.id.notice_text).text = ""
            showPage(Page.NOTICES)
            return
        }
        noticeIndex = if (page != Page.NOTICES) 0 else (noticeIndex + 1) % notices.size
        displayNotice(noticeIndex, auto = false)
    }

    /**
     * Toque en la página del mapa: abre Google Maps del S40. Mientras está abierto usa
     * el GPS y la pantalla del S40; se regresa a Rodada con el botón de atrás.
     */
    private fun openMap() {
        val intent = packageManager.getLaunchIntentForPackage(MAPS_PACKAGE)
        if (intent == null) {
            Toast.makeText(this, R.string.maps_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    /** auto = llegó sola: vuelve al centro a los [NOTICE_MS]. Abierta a mano se queda. */
    private fun displayNotice(index: Int, auto: Boolean = true) {
        if (currentCall != null) return
        val notif = notices[index]
        findViewById<LedTextView>(R.id.notice_app).text =
            if (notices.size > 1) getString(R.string.notice_position, notif.app, index + 1, notices.size) else notif.app
        findViewById<LedTextView>(R.id.notice_title).text = notif.titulo
        findViewById<LedTextView>(R.id.notice_text).text = notif.texto
        showPage(Page.NOTICES)
        if (auto) handler.postDelayed(hideNotice, NOTICE_MS)
    }

    /** Cambia de página; cancela la vuelta automática pendiente. */
    private fun showPage(target: Page) {
        handler.removeCallbacks(hideNotice)
        page = target
        val visible = android.view.View.VISIBLE
        val gone = android.view.View.GONE
        notice.visibility = if (target == Page.NOTICES) visible else gone
        findViewById<android.view.View>(R.id.nav_block).visibility = if (target == Page.MAP) visible else gone
        findViewById<android.view.View>(R.id.speed_block).visibility = if (target == Page.CENTER) visible else gone
        findViewById<android.view.View>(R.id.stats_row).visibility = if (target == Page.CENTER) visible else gone
        if (target == Page.MAP) updateNavViews()
    }

    /** Un toque sobre el aviso: dice el texto del mensaje que se está viendo. */
    private fun dismissNotices() {
        if (unread == 0 && page != Page.NOTICES) return
        unread = 0
        showUnread()
        handler.removeCallbacks(hideNotice)
        handler.post(hideNotice)
    }

    private fun showUnread() {
        unreadBadge.text = getString(R.string.unread_badge, unread)
        unreadBadge.visibility = if (unread > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun readShownNotice() {
        val notif = notices.getOrNull(noticeIndex) ?: return
        // Escuchar uno marca como leídos ese y los más viejos.
        if (noticeIndex < unread) {
            unread = noticeIndex
            showUnread()
        }
        say(
            if (notif.texto.isBlank()) getString(R.string.speech_message_empty)
            else getString(R.string.speech_message_says, notif.texto)
        )
        // Ya leído: se quita de la pantalla; la voz termina de decirlo.
        handler.removeCallbacks(hideNotice)
        handler.post(hideNotice)
    }

    /** Dice un texto con la voz elegida: el Motorola si hay enlace, si no este teléfono. */
    private fun say(text: String) {
        val link = enlace
        if (isVoiceOnMotorola() && link != null && link.conectado) {
            link.enviar(Mensaje.Decir(text))
        } else {
            speech.speakText(text)
            if (isVoiceOnMotorola()) link?.reintentar()
        }
    }

    private fun sayLastNotice() {
        val notif = notices.firstOrNull()
        val text = if (notif == null) {
            getString(R.string.speech_no_notification)
        } else {
            getString(R.string.speech_notification, notif.app, notif.titulo) +
                if (notif.texto.isBlank()) "" else " " + getString(R.string.speech_notification_text, notif.texto)
        }
        say(text)
    }

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
            // Sin sondeo: se reintenta el enlace solo cuando lo usas.
            if (isVoiceOnMotorola()) link?.reintentar()
        }
    }

    /** El enlace se abre si ya hay un Motorola emparejado (lo guarda el control HID) y permiso. */
    private fun startLinkIfPossible() {
        val address = prefs.getString(KEY_MOTOROLA_ADDRESS, null)
        if (enlace != null || address == null || !hasBluetoothPermissions()) {
            showLinkStatus()
            return
        }
        val link = EnlaceCliente(this, address, { estado ->
            if (estado != EnlaceCliente.Estado.CONECTADO) {
                linkSong = null
                hideNav()
                currentCall?.let { endCall(it.id) }
            }
            showLinkStatus()
            updateTicker()
        }) { mensaje ->
            when (mensaje) {
                is Mensaje.Notif -> showNotice(mensaje)
                is Mensaje.Cancion -> {
                    linkSong = mensaje
                    updateTicker()
                }
                is Mensaje.Nav -> showNav(mensaje)
                Mensaje.NavFin -> hideNav()
                is Mensaje.Llamada -> showCall(mensaje)
                is Mensaje.LlamadaEnCurso -> showActiveCall(mensaje)
                Mensaje.Buscar -> ringFinder()
                Mensaje.BuscarFin -> {
                    finder.detener()
                    findingMotorola = false
                }
                is Mensaje.LlamadaFin -> endCall(mensaje.id)
                is Mensaje.Hola, is Mensaje.Decir, is Mensaje.Responder -> Unit
            }
        }
        enlace = link
        link.iniciar()
        showLinkStatus()
    }

    /** Muestra la notificación unos segundos con letra grande y vibra. No se guarda. */
    private fun showNotice(notif: Mensaje.Notif) {
        notices.addFirst(notif)
        while (notices.size > MAX_NOTICES) notices.removeLast()
        unread = (unread + 1).coerceAtMost(notices.size)
        showUnread()
        noticeIndex = 0
        displayNotice(0)
        wakeScreen()
        ticker.mostrarUnaVez(getString(R.string.ticker_notice, notif.app, notif.titulo))
        say(getString(R.string.speech_new_message, notif.titulo.ifBlank { notif.app }))
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 120), -1))
        }
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
        startLocalMusic()
        updateTicker()
    }

    /** Suena cuando el Motorola busca este S40. */
    private val finder by lazy {
        Timbre(this).apply {
            alDetener = {
                enlace?.enviar(Mensaje.BuscarFin)
                findWakeLock?.let { if (it.isHeld) it.release() }
                findWakeLock = null
                if (page == Page.NOTICES && currentCall == null) showPage(Page.CENTER)
            }
        }
    }
    private var findWakeLock: android.os.PowerManager.WakeLock? = null
    /** Se pidió que el Motorola suene (para el texto del menú). */
    private var findingMotorola = false
    private val findingMotorolaOff = Runnable { findingMotorola = false }

    /** El Motorola busca este S40: alarma fuerte, pantalla encendida y aviso. */
    private fun ringFinder() {
        finder.sonar()
        if (findWakeLock == null) {
            @Suppress("DEPRECATION")
            findWakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Rodada:buscar"
            ).apply { acquire(Timbre.DURACION_MS) }
        }
        if (currentCall == null) {
            findViewById<LedTextView>(R.id.notice_app).text = ""
            findViewById<LedTextView>(R.id.notice_title).setText(R.string.find_title)
            findViewById<LedTextView>(R.id.notice_text).setText(R.string.find_touch_to_stop)
            showPage(Page.NOTICES)
        }
        wakeScreen()
    }

    /** Menú ☰: que suene el Motorola (o que deje de sonar). */
    private fun toggleFindMotorola() {
        val link = enlace
        if (link == null || !link.conectado) {
            Toast.makeText(this, R.string.find_no_link, Toast.LENGTH_SHORT).show()
            return
        }
        findingMotorola = !findingMotorola
        link.enviar(if (findingMotorola) Mensaje.Buscar else Mensaje.BuscarFin)
        handler.removeCallbacks(findingMotorolaOff)
        if (findingMotorola) handler.postDelayed(findingMotorolaOff, Timbre.DURACION_MS)
        Toast.makeText(this, if (findingMotorola) R.string.find_sent else R.string.find_stopped, Toast.LENGTH_SHORT).show()
    }

    /** Llamada sonando en el Motorola; el aviso queda fijo hasta LLAMADA_FIN. */
    private var currentCall: Mensaje.Llamada? = null
    /** Llamada que rechazaste con la rueda, para mostrar "TE MARCÓ" al terminar. */
    private var rejectedCallId: String? = null
    /** false = sonando; true = contestada (en curso). */
    private var callActive = false

    /** "LLAMADA DE NOMBRE" en grande, vibración larga repetida y pantalla encendida. */
    private fun showCall(call: Mensaje.Llamada) {
        currentCall = call
        val name = call.nombre.ifBlank { getString(R.string.call_unknown) }
        findViewById<LedTextView>(R.id.notice_app).text = call.app
        findViewById<LedTextView>(R.id.notice_title).setText(R.string.call_from)
        findViewById<LedTextView>(R.id.notice_text).text = name
        showPage(Page.NOTICES)
        callActive = false
        findViewById<MusicWheelView>(R.id.music_wheel).callMode = MusicWheelView.CallMode.RINGING
        // Mientras suena: brillo al máximo, sin volver a tenue ni al centro.
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        handler.removeCallbacks(dimScreen)
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        }
        say(getString(R.string.speech_call, name))
    }

    /** Llamada contestada: "HABLANDO CON nombre" y la rueda pasa a colgar y altavoz. */
    private fun showActiveCall(call: Mensaje.LlamadaEnCurso) {
        currentCall = Mensaje.Llamada(call.id, call.app, call.nombre)
        callActive = true
        vibrator.cancel()
        findViewById<LedTextView>(R.id.notice_app).text = call.app
        findViewById<LedTextView>(R.id.notice_title).setText(R.string.call_active)
        findViewById<LedTextView>(R.id.notice_text).text = call.nombre.ifBlank { getString(R.string.call_unknown) }
        showPage(Page.NOTICES)
        val wheel = findViewById<MusicWheelView>(R.id.music_wheel)
        wheel.speakerAvailable = call.altavoz
        wheel.muteAvailable = call.silenciar
        wheel.callMode = MusicWheelView.CallMode.ACTIVE
        wakeScreen()
    }

    /** Rueda del S40: el Motorola aprieta ese botón en la notificación de la llamada. */
    private fun callAction(action: Mensaje.Responder.Accion) {
        val call = currentCall ?: return
        vibrator.cancel()
        if (enlace?.enviar(Mensaje.Responder(call.id, action)) != true) {
            Toast.makeText(this, R.string.motorola_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        if (action == Mensaje.Responder.Accion.RECHAZAR) rejectedCallId = call.id
    }

    private fun endCall(id: String) {
        val endedCall = currentCall
        if (endedCall?.id != id) return
        currentCall = null
        callActive = false
        findViewById<MusicWheelView>(R.id.music_wheel).callMode = MusicWheelView.CallMode.NONE
        vibrator.cancel()
        wakeScreen()
        val call = endedCall
        if (rejectedCallId == id) {
            // Rechazada desde la rueda: queda el aviso "TE MARCÓ nombre" con las notificaciones.
            rejectedCallId = null
            notices.addFirst(
                Mensaje.Notif(call.app, getString(R.string.call_rejected), call.nombre.ifBlank { getString(R.string.call_unknown) })
            )
            while (notices.size > MAX_NOTICES) notices.removeLast()
            noticeIndex = 0
            displayNotice(0)
        } else {
            showPage(Page.CENTER)
        }
    }

    /** Última navegación recibida, para mostrar la velocidad en su línea de abajo. */
    private var currentNav: Mensaje.Nav? = null

    /** Pantalla de navegación: flecha grande, distancia, instrucción y resumen. */
    private fun showNav(nav: Mensaje.Nav) {
        currentNav = nav
        val maneuverChanged = nav.instruccion != lastManeuver
        lastManeuver = nav.instruccion
        updateNavViews()
        if (maneuverChanged) {
            // Maniobra nueva: salta al mapa un rato (salvo si estás leyendo una notificación).
            if (page != Page.NOTICES) {
                showPage(Page.MAP)
                handler.postDelayed(hideNotice, NOTICE_MS)
            }
            wakeScreen()
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
            }
        }
    }

    private fun hideNav() {
        if (currentNav == null) return
        currentNav = null
        lastManeuver = null
        updateNavViews()
    }

    /** Página del mapa: la ruta del Motorola, o "Sin ruta" si Maps no está navegando. */
    private fun updateNavViews() {
        val nav = currentNav
        val arrow = findViewById<NavArrowView>(R.id.nav_arrow)
        if (nav == null) {
            arrow.visibility = android.view.View.INVISIBLE
            findViewById<LedTextView>(R.id.nav_distance).setText(R.string.nav_no_route)
            findViewById<LedTextView>(R.id.nav_instruction).setText(R.string.nav_tap_to_open)
            findViewById<LedTextView>(R.id.nav_summary).text = ""
            return
        }
        arrow.visibility = android.view.View.VISIBLE
        arrow.turn = turnFor(nav.instruccion)
        findViewById<LedTextView>(R.id.nav_distance).text = nav.distancia.ifBlank { shortDistance(nav.resumen) }
        findViewById<LedTextView>(R.id.nav_instruction).text = nav.instruccion
        updateNavSummary()
    }

    /** "23 km/h · 3 min · 800 m · Llegada: 5:35 p. m." (la velocidad viene del GPS del S40). */
    private fun updateNavSummary() {
        val nav = currentNav ?: return
        val speedText = getString(R.string.nav_speed, RideState.speedKmh.toInt())
        findViewById<LedTextView>(R.id.nav_summary).text =
            if (nav.resumen.isBlank()) speedText else "$speedText · ${nav.resumen}"
    }

    private fun turnFor(instruction: String): NavArrowView.Turn {
        val text = instruction.lowercase()
        return when {
            "destino" in text || "llegaste" in text || "has llegado" in text -> NavArrowView.Turn.ARRIVE
            "vuelta en u" in text || "retorno" in text || "cambio de sentido" in text -> NavArrowView.Turn.UTURN
            ("ligeramente" in text || "mantente" in text || "incorpórate" in text) && "izquierda" in text ->
                NavArrowView.Turn.SLIGHT_LEFT
            ("ligeramente" in text || "mantente" in text || "incorpórate" in text) && "derecha" in text ->
                NavArrowView.Turn.SLIGHT_RIGHT
            "izquierda" in text -> NavArrowView.Turn.LEFT
            "derecha" in text -> NavArrowView.Turn.RIGHT
            else -> NavArrowView.Turn.STRAIGHT
        }
    }

    /** De "3 min · 800 m · Llegada: 5:35 p. m." deja "800 m". */
    private fun shortDistance(raw: String): String {
        val parts = raw.split('·').map { it.trim() }
        return parts.firstOrNull { Regex("""^\d+([.,]\d+)?\s?(m|km)$""").matches(it) } ?: ""
    }

    /** Brillo al máximo un rato; luego vuelve a tenue. Toque, notificación o maniobra. */
    private fun wakeScreen() {
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        handler.removeCallbacks(dimScreen)
        handler.postDelayed(dimScreen, BRIGHT_MS)
    }

    private fun setBrightness(value: Float) {
        setLcdBacklight(value != DIM_BRIGHTNESS)
        val attributes = window.attributes
        if (attributes.screenBrightness == value) return
        attributes.screenBrightness = value
        window.attributes = attributes
    }

    private fun setLcdBacklight(backlit: Boolean) {
        if (lcdBacklit == backlit) return
        lcdBacklit = backlit
        findViewById<android.view.View>(R.id.info_panel).setBackgroundResource(
            if (backlit) R.drawable.info_panel_background else R.drawable.info_panel_day
        )
        findViewById<android.view.View>(R.id.ticker).setBackgroundResource(
            if (backlit) R.drawable.ticker_background else R.drawable.ticker_day
        )
        findViewById<android.view.View>(R.id.notice).setBackgroundResource(
            if (backlit) R.drawable.notice_background else R.drawable.notice_day
        )
        fun update(view: android.view.View) {
            when (view) {
                is LedTextView -> view.setBacklit(backlit)
                is LedTickerView -> view.setBacklit(backlit)
                is NavArrowView -> view.setBacklit(backlit)
                is android.view.ViewGroup -> for (index in 0 until view.childCount) update(view.getChildAt(index))
            }
        }
        update(findViewById(R.id.screen_half))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Cambiar el brillo a mitad de un toque hace que Android cancele el gesto. Si la
        // pantalla está tenue, se ilumina al levantar el dedo y el gesto se completa.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && finder.sonando) {
            finder.detener()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (window.attributes.screenBrightness == DIM_BRIGHTNESS) wakeOnUp = true else wakeScreen()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (wakeOnUp) {
                wakeOnUp = false
                handler.post { wakeScreen() }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Franja con destino "este teléfono": lee la música del S40 si hay acceso. */
    private fun startLocalMusic() {
        if (localMusic != null) return
        val music = SesionMusica(this, android.content.ComponentName(this, MusicaS40Service::class.java)) { updateTicker() }
        if (music.iniciar()) localMusic = music
    }

    private fun stopLocalMusic() {
        localMusic?.detener()
        localMusic = null
    }

    private fun updateTicker() {
        if (!::ticker.isInitialized) return
        if (!isMotorolaTarget()) {
            MusicaS40Service.currentStation?.let { station ->
                ticker.mostrar(getString(R.string.ticker_radio, station.removeSuffix("MHz")), false, true)
                return
            }
        }
        val song: Mensaje.Cancion? = if (isMotorolaTarget()) {
            val link = enlace
            if (link == null || !link.conectado) {
                ticker.mostrar(getString(R.string.ticker_no_link), false)
                return
            }
            linkSong
        } else {
            val local = localMusic
            if (local == null) {
                ticker.mostrar(getString(R.string.ticker_no_access), true)
                return
            }
            local.actual
        }
        val text = song?.let { if (it.artista.isBlank()) it.titulo else getString(R.string.ticker_song, it.artista, it.titulo) }
        when (song?.estado) {
            Mensaje.Cancion.Estado.REPRODUCIENDO -> ticker.mostrar(text.orEmpty(), true)
            Mensaje.Cancion.Estado.PAUSA -> ticker.mostrar(getString(R.string.ticker_paused, text.orEmpty()), false)
            null, Mensaje.Cancion.Estado.NADA -> ticker.mostrar(getString(R.string.ticker_no_music), false)
        }
    }

    /** Tocar la franja sin acceso: explicar y abrir el ajuste del permiso. */
    private fun onTickerTapped() {
        if (isMotorolaTarget() || localMusic != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.music_access_title)
            .setMessage(R.string.music_access_explanation)
            .setPositiveButton(R.string.permissions_continue) { _, _ ->
                startActivity(
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        android.content.ComponentName(this, MusicaS40Service::class.java).flattenToString()
                    )
                )
            }
            .setNegativeButton(R.string.permissions_cancel, null)
            .show()
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
        stopLocalMusic()
        updateTicker()
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
        if (connected && !wasConnected) enlace?.reintentar()
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
        private const val MENU_FIND_GROUP = 3
        private const val MENU_FIND = 5
        private const val KEY_VOICE = "voz_en"
        private const val NOTICE_MS = 20_000L
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val BRIGHT_MS = 10_000L
        private const val DIM_BRIGHTNESS = 0.06f
        private const val MAX_NOTICES = 5
        private const val SWIPE_MIN_PX = 60
        private const val COLOR_OFF = 0xFF9E9E9E.toInt()
        private const val COLOR_NORMAL = 0xFFFFFFFF.toInt()
        private const val COLOR_OK = 0xFF4CAF50.toInt()
        private const val COLOR_WARNING = 0xFFFFC107.toInt()
        private const val COLOR_LED_BLUE = 0xFF2EA8FF.toInt()
        private const val COLOR_ERROR = 0xFFFF5252.toInt()
        private val BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }
}
