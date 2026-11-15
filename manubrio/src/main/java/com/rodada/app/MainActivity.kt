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
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.rodada.enlace.Mensaje
import com.rodada.enlace.SesionMusica
import com.rodada.enlace.Bitacora
import com.rodada.enlace.Timbre

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var speed: LedTextView
    private lateinit var speedUnit: LedTextView
    private lateinit var distance: LedTextView
    private lateinit var elapsed: LedTextView
    private var lastAudioRoute: Boolean? = null
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
    private var rodadaVisible = false
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
        // En el manubrio no se suspende mientras Rodada está a la vista, con o sin
        // recorrido y sin cargador; el brillo ya baja solo al minuto. El botón de
        // encendido la sigue apagando, y al salir de Rodada vuelve la suspensión normal.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOrientation(resources.configuration)
        speed = findViewById(R.id.speed)
        speedUnit = findViewById(R.id.speed_unit)
        distance = findViewById(R.id.distance)
        elapsed = findViewById(R.id.elapsed)
        audioManager = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        val infoPanel = findViewById<LinearLayout>(R.id.info_panel)
        // Un toque: hora. Dos toques: última notificación. Tres toques: clima.
        // Mantener: iniciar o detener recorrido.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Tercer toque justo después de un doble toque: clima en vez de la notificación.
                if (doubleTapPending) {
                    handler.removeCallbacks(doubleTapAction)
                    doubleTapPending = false
                    ignoreNextTap = true
                    tellWeather()
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (ignoreNextTap) {
                    ignoreNextTap = false
                    return true
                }
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
                if (ignoreNextTap) {
                    ignoreNextTap = false
                    return true
                }
                // Se espera un instante por si llega el tercer toque.
                doubleTapPending = true
                handler.postDelayed(doubleTapAction, TRIPLE_TAP_WAIT_MS)
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
        findViewById<ImageButton>(R.id.audio_button).apply {
            setOnClickListener {
                val receiver = AudioBridgeReceiver.current
                if (receiver == null) startAudioReceiver()
                else stopService(Intent(this@MainActivity, AudioBridgeReceiver::class.java))
                updateAudioButton()
            }
        }
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
        rodadaVisible = true
        handler.removeCallbacks(updateScreen)
        updateScreen.run()
        // Al volver de Ajustes con el acceso recién concedido.
        if (!isMotorolaTarget() && localMusic == null) startLocalMusic()
        ticker.reanudar()
        wakeScreen()
        // Temperatura: Android avisa cuando cambia (sin sondeo).
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        handler.removeCallbacks(shiftPixels)
        handler.postDelayed(shiftPixels, PIXEL_SHIFT_MS)
    }

    override fun onPause() {
        rodadaVisible = false
        handler.removeCallbacks(updateScreen)
        // Pantalla apagada o app en segundo plano: la franja deja de animar.
        ticker.pausar()
        handler.removeCallbacks(dimScreen)
        handler.removeCallbacks(shiftPixels)
        unregisterReceiver(batteryReceiver)
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
        fallAlarm.detener()
        guard.desarmar()
        guardAlarm.detener()
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
        updateAudioButton()
        if (RideState.running && !RideState.hasFix) {
            speed.text = getString(R.string.searching_gps)
            speedUnit.text = if (RideState.accuracyMeters > 0f) {
                getString(R.string.gps_accuracy, RideState.accuracyMeters.toInt())
            } else {
                getString(R.string.gps_satellites, Satelites.vistos, Satelites.usados)
            }
        } else {
            speed.text = getString(R.string.speed_value, RideState.speedKmh.toInt())
            speedUnit.setText(R.string.speed_unit)
        }
        distance.text = getString(R.string.distance_value, RideState.distanceMeters / 1000f)
        updateNavSummary()
        val seconds = (SystemClock.elapsedRealtime() - sessionStartedAtMs) / 1000L
        elapsed.text = getString(R.string.elapsed_value, seconds / 3600, (seconds / 60) % 60, seconds % 60)
        updateDayNight()
        val floodMeters = RideState.floodAlertMeters
        if (floodMeters >= 0) {
            RideState.floodAlertMeters = -1
            warnFlood(floodMeters)
        }
    }

    private var doubleTapPending = false
    private var ignoreNextTap = false
    private val doubleTapAction = Runnable {
        doubleTapPending = false
        sayLastNotice()
    }
    /** Último clima recibido y cuándo; se dice al tocar tres veces. */
    private var lastWeather: Mensaje.Clima? = null
    private var lastWeatherAtMs = 0L
    private var weatherRequested = false
    private val weatherTimeout = Runnable {
        if (weatherRequested) {
            weatherRequested = false
            say(getString(R.string.speech_weather_failed))
        }
    }

    /** Tres toques: el clima en voz. Si el último es reciente no se consulta de nuevo. */
    private fun tellWeather() {
        val weather = lastWeather
        if (weather != null && System.currentTimeMillis() - lastWeatherAtMs < WEATHER_FRESH_MS) {
            showWeather(weather)
            say(weatherSentence(weather))
            return
        }
        val link = enlace
        if (link == null || !link.conectado) {
            say(getString(R.string.speech_weather_no_link))
            return
        }
        weatherRequested = true
        link.enviar(Mensaje.PedirClima)
        ticker.mostrarUnaVez(getString(R.string.weather_asking))
        handler.removeCallbacks(weatherTimeout)
        handler.postDelayed(weatherTimeout, WEATHER_WAIT_MS)
    }

    private fun weatherSentence(weather: Mensaje.Clima): String {
        val parts = mutableListOf(getString(R.string.speech_weather, weather.tempC, weather.descripcion))
        when {
            weather.lluviaEnMin == 0 -> parts += getString(R.string.speech_weather_raining)
            weather.lluviaEnMin in 1..RAIN_SOON_MIN -> parts += getString(R.string.speech_rain_soon, weather.lluviaEnMin)
            else -> parts += getString(R.string.speech_weather_no_rain)
        }
        if (weather.vientoKmh >= WINDY_KMH) parts += getString(R.string.speech_weather_wind, weather.vientoKmh)
        return parts.joinToString(". ")
    }

    /**
     * Fondo de día (blanco) o de noche (negro) según el sol donde estás: salida y
     * puesta calculadas con la última ubicación del GPS; sin ubicación, 7:00 a 19:00.
     * Se revisa una vez por minuto.
     */
    private var isDay: Boolean? = null
    private var dayNightCheckedMs = 0L
    private fun updateDayNight() {
        val now = System.currentTimeMillis()
        if (isDay != null && now - dayNightCheckedMs < DAY_NIGHT_CHECK_MS) return
        dayNightCheckedMs = now
        val point = UbicacionS40.leer(this)
        val day = if (point != null) {
            Sol.esDeDia(point.latitud, point.longitud, now)
        } else {
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 7 until 19
        }
        if (day == isDay) return
        isDay = day
        findViewById<android.view.View>(R.id.root).setBackgroundColor(getColor(if (day) R.color.case_day else R.color.case_night))
        val text = normalTextColor()
        findViewById<TextView>(R.id.audio_route_status).setTextColor(text)
        // El indicador del destino solo usa el color normal cuando no hay estado especial.
        when (targetStatus.currentTextColor) {
            COLOR_NORMAL, COLOR_NORMAL_DAY -> targetStatus.setTextColor(text)
            COLOR_WARNING, COLOR_WARNING_DAY -> targetStatus.setTextColor(warningColor())
        }
        showLinkStatus()
        applyLcdStyle()
    }

    private fun normalTextColor(): Int = if (isDay == true) COLOR_NORMAL_DAY else COLOR_NORMAL

    /** El ámbar no se lee sobre blanco: de día uno más oscuro. */
    private fun warningColor(): Int = if (isDay == true) COLOR_WARNING_DAY else COLOR_WARNING

    /** Encharcamiento cerca y llovió: franja, voz y vibración. */
    private fun warnFlood(meters: Int) {
        Bitacora.anotar(this, "ENCHARCAMIENTO_AVISO", meters.toDouble())
        ticker.mostrarUnaVez(getString(R.string.flood_ahead, meters))
        say(getString(R.string.speech_flood, meters))
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
        }
        wakeScreen()
    }

    /** Clima del Motorola: una pasada en la franja y voz solo si viene lluvia. */
    private fun showWeather(weather: Mensaje.Clima) {
        RideState.rainRecent = weather.llovio
        Bitacora.anotar(
            this, "CLIMA", weather.tempC.toDouble(),
            "llovio=${weather.llovio} lluvia_en=${weather.lluviaEnMin} viento=${weather.vientoKmh}"
        )
        if (weather !== lastWeather) {
            lastWeather = weather
            lastWeatherAtMs = System.currentTimeMillis()
        }
        if (weatherRequested) {
            // Respuesta a los tres toques: se dice completo (y no se repite lo de la lluvia).
            weatherRequested = false
            handler.removeCallbacks(weatherTimeout)
            ticker.mostrarUnaVez(weatherLine(weather))
            say(weatherSentence(weather))
            return
        }
        ticker.mostrarUnaVez(weatherLine(weather))
        if (weather.lluviaEnMin in 1..60) say(getString(R.string.speech_rain_soon, weather.lluviaEnMin))
    }

    /** "26 C NUBES DISPERSAS - LLUVIA EN 40 MIN" para la franja LED. */
    private fun weatherLine(weather: Mensaje.Clima): String {
        val parts = mutableListOf(getString(R.string.weather_now, weather.tempC, weather.descripcion.uppercase()))
        if (weather.vientoKmh >= WINDY_KMH) parts += getString(R.string.weather_wind, weather.vientoKmh)
        when {
            weather.lluviaEnMin == 0 -> parts += getString(R.string.weather_raining)
            weather.lluviaEnMin in 1..RAIN_SOON_MIN -> parts += getString(R.string.weather_rain_in, weather.lluviaEnMin)
        }
        val toSunset = (weather.puestaSolMs - System.currentTimeMillis()) / 60_000L
        if (weather.puestaSolMs > 0L && toSunset in 0..SUNSET_SOON_MIN) parts += getString(R.string.weather_sunset, toSunset)
        return parts.joinToString(" - ")
    }

    private fun startAudioReceiver() {
        val address = prefs.getString(KEY_MOTOROLA_ADDRESS, null)
        if (address == null) {
            Toast.makeText(this, R.string.audio_no_motorola, Toast.LENGTH_SHORT).show()
            return
        }
        withBluetoothPermission {
            startForegroundService(Intent(this, AudioBridgeReceiver::class.java)
                .putExtra(AudioBridgeReceiver.EXTRA_ADDRESS, address))
        }
    }

    private fun updateAudioButton() {
        val button = findViewById<ImageButton>(R.id.audio_button)
        val active = AudioBridgeReceiver.state == AudioBridgeReceiver.State.PLAYING
        val connecting = AudioBridgeReceiver.state == AudioBridgeReceiver.State.CONNECTING
        button.setImageResource(if (active || connecting) R.drawable.ic_audio_speaker else R.drawable.ic_audio_headphones)
        findViewById<TextView>(R.id.audio_route_status).setText(
            if (active || connecting) R.string.audio_route_s40_short else R.string.audio_route_motorola_short
        )
        if (lastAudioRoute != null && lastAudioRoute != active && !connecting) {
            ticker.mostrarUnaVez(getString(if (active) R.string.audio_route_s40 else R.string.audio_route_motorola))
        }
        lastAudioRoute = active
        button.contentDescription = getString(
            if (AudioBridgeReceiver.current != null) R.string.audio_button_stop else R.string.audio_button_start
        )
        button.alpha = if (active) 1f else if (connecting) 0.8f else 0.7f
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
        if (guard.estado != Guardia.Estado.APAGADO) {
            enterGuardCode(zone)
            return
        }
        // Alarma de caída: derecha (verde) = estoy bien, izquierda (roja) = necesito ayuda.
        if (fallAlarm.sonando) {
            when (zone) {
                MusicWheelView.Zone.NEXT -> stopFall(tellMotorola = true)
                MusicWheelView.Zone.PREVIOUS -> askForHelp()
                else -> Unit
            }
            return
        }
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
        if (AudioBridgeReceiver.current != null &&
            (zone == MusicWheelView.Zone.UP || zone == MusicWheelView.Zone.DOWN)
        ) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (zone == MusicWheelView.Zone.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            ticker.mostrarUnaVez(getString(
                if (zone == MusicWheelView.Zone.UP) R.string.volume_up_feedback else R.string.volume_down_feedback
            ))
            if (vibrator.hasVibrator()) vibrator.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
            return
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
        content.findViewById<TextView>(R.id.mode_dim).apply {
            text = getString(R.string.mode_dim_value, dimLabel(brightMs()))
            // Cada toque pasa a la siguiente opción; se aplica en el momento.
            setOnClickListener {
                val next = DIM_OPTIONS_MS[(DIM_OPTIONS_MS.indexOf(brightMs()) + 1) % DIM_OPTIONS_MS.size]
                prefs.edit().putLong(KEY_DIM_MS, next).apply()
                text = getString(R.string.mode_dim_value, dimLabel(next))
                wakeScreen()
            }
        }
        OdometerStore(this).use { store ->
            content.findViewById<TextView>(R.id.mode_odometer).text =
                getString(R.string.mode_odometer_value, store.totalMeters() / 1000.0)
            // Recorrido en curso (en vivo) o el último guardado, más el récord.
            val ride = if (RideState.running) {
                OdometerStore.Ride(
                    RideState.elapsedSeconds(), RideState.distanceMeters.toDouble(),
                    RideState.maxKmh, RideState.minMovingKmh, RideState.stoppedMs / 1000L, RideState.stops
                )
            } else {
                store.lastRide()
            }
            content.findViewById<TextView>(R.id.mode_ride_label).setText(
                if (RideState.running) R.string.mode_ride_now else R.string.mode_ride_last
            )
            val record = maxOf(store.recordMaxKmh(), if (RideState.running) RideState.maxKmh else 0f)
            content.findViewById<TextView>(R.id.mode_ride).text = if (ride == null) {
                getString(R.string.mode_ride_none)
            } else {
                getString(
                    R.string.mode_ride_value,
                    ride.maxKmh.toInt(),
                    if (ride.minKmh < 0f) "-" else ride.minKmh.toInt().toString(),
                    ride.averageKmh.toInt(),
                    ride.stoppedSeconds / 60, ride.stoppedSeconds % 60,
                    record.toInt(), ride.stops
                )
            }
        }
        content.findViewById<TextView>(R.id.mode_history).setOnClickListener { showHistory() }
        content.findViewById<TextView>(R.id.mode_guard).setOnClickListener { dialog.dismiss(); showGuardMenu() }
        content.findViewById<TextView>(R.id.mode_flood).apply {
            text = getString(R.string.mode_flood, OdometerStore(this@MainActivity).use { it.floodSpots().size })
            setOnClickListener { dialog.dismiss(); showFloodMenu() }
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
                Mensaje.PedirUbicacion -> UbicacionS40.solicitar(this, rodadaVisible)
                is Mensaje.Caida -> ringFall(mensaje)
                is Mensaje.Clima -> showWeather(mensaje)
                Mensaje.CaidaOk -> stopFall(tellMotorola = false)
                is Mensaje.CaidaAviso -> fallFinished(mensaje)
                is Mensaje.Hola, is Mensaje.Decir, is Mensaje.Responder, is Mensaje.Ubicacion,
                is Mensaje.Recorrido, Mensaje.Frenon, Mensaje.CaidaAyuda, Mensaje.PedirClima -> Unit
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
                linkStatus.setTextColor(warningColor())
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
        targetStatus.setTextColor(normalTextColor())
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

    /** MODE → ENCHARCAMIENTOS: marcar donde estás o borrar todos. */
    private fun showFloodMenu() {
        val options = arrayOf(getString(R.string.flood_mark), getString(R.string.flood_clear))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.flood_title)
            .setItems(options) { _, which ->
                if (which == 0) markFloodHere() else confirmClearFloods()
            }
            .setNegativeButton(R.string.mode_close, null)
            .show()
    }

    private fun markFloodHere() {
        val point = UbicacionS40.leer(this)?.takeIf {
            System.currentTimeMillis() - it.horaMs <= FLOOD_FIX_MAX_AGE_MS && it.precisionMetros <= FLOOD_FIX_MAX_METERS
        }
        if (point == null) {
            Toast.makeText(this, R.string.flood_no_gps, Toast.LENGTH_LONG).show()
            return
        }
        OdometerStore(this).use { it.addFloodSpot(point.latitud, point.longitud) }
        RideState.floodSpotsVersion++
        Toast.makeText(this, R.string.flood_saved, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearFloods() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.flood_clear)
            .setMessage(R.string.flood_clear_message)
            .setNegativeButton(R.string.permissions_cancel, null)
            .setPositiveButton(R.string.flood_clear) { _, _ ->
                OdometerStore(this).use { it.clearFloodSpots() }
                RideState.floodSpotsVersion++
            }
            .show()
    }

    // ===================== Modo guardia =====================

    private val guard by lazy { Guardia(this) { reason -> guardAlert(reason) } }
    private val guardAlarm by lazy { Timbre(this) }
    private var guardWakeLock: android.os.PowerManager.WakeLock? = null
    private val guardCode = ArrayDeque<MusicWheelView.Zone>()
    private var guardCodeStartedMs = 0L

    /** MODE → MODO GUARDIA: activar, o probar foto y Telegram. */
    private fun showGuardMenu() {
        // Con mensaje, AlertDialog no muestra listas: van como botones.
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.guard_title)
            .setMessage(R.string.guard_explain)
            .setPositiveButton(R.string.guard_activate) { _, _ -> startGuard() }
            .setNeutralButton(R.string.guard_test) { _, _ -> testGuard() }
            .setNegativeButton(R.string.mode_close, null)
            .show()
    }

    private fun withCamera(action: () -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCameraAction = action
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }
    private var pendingCameraAction: (() -> Unit)? = null

    private fun startGuard() = withCamera {
        if (RideState.running) {
            Toast.makeText(this, R.string.guard_stop_ride, Toast.LENGTH_LONG).show()
            return@withCamera
        }
        guard.armar()
        guardCode.clear()
        ticker.mostrar(getString(R.string.guard_on_ticker), desplazar = true)
        findViewById<LedTextView>(R.id.notice_app).text = ""
        findViewById<LedTextView>(R.id.notice_title).setText(R.string.guard_arming)
        findViewById<LedTextView>(R.id.notice_text).setText(R.string.guard_code_hint)
        showPage(Page.NOTICES)
        say(getString(R.string.speech_guard_arming, (Guardia.ESPERA_MS / 1000).toInt()))
    }

    /** Código en la rueda: arriba, arriba, anterior, siguiente. */
    private fun enterGuardCode(zone: MusicWheelView.Zone) {
        val now = SystemClock.elapsedRealtime()
        if (now - guardCodeStartedMs > GUARD_CODE_WINDOW_MS) guardCode.clear()
        if (guardCode.isEmpty()) guardCodeStartedMs = now
        guardCode.addLast(zone)
        while (guardCode.size > GUARD_CODE.size) guardCode.removeFirst()
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
        if (guardCode.toList() == GUARD_CODE) stopGuard()
    }

    private fun stopGuard() {
        guard.desarmar()
        guardAlarm.detener()
        guardWakeLock?.let { if (it.isHeld) it.release() }
        guardWakeLock = null
        guardCode.clear()
        updateTicker()
        showPage(Page.CENTER)
        say(getString(R.string.speech_guard_off))
    }

    /** La movieron: alarma, pantalla encendida, fotos y Telegram. */
    private fun guardAlert(reason: String) {
        guardAlarm.sonar(GUARD_ALARM_MS)
        if (guardWakeLock == null) {
            @Suppress("DEPRECATION")
            guardWakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Rodada:guardia_alerta"
            )
        }
        guardWakeLock?.acquire(GUARD_ALARM_MS)
        findViewById<LedTextView>(R.id.notice_app).text = ""
        findViewById<LedTextView>(R.id.notice_title).setText(R.string.guard_alert_title)
        findViewById<LedTextView>(R.id.notice_text).setText(R.string.guard_alert_text)
        showPage(Page.NOTICES)
        wakeScreen()
        enlace?.enviar(Mensaje.Decir(getString(R.string.speech_guard_alert)))
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        takeGuardPhotos(GUARD_PHOTOS) { photos ->
            Telegram.enviar(this, getString(R.string.guard_telegram_alert, time, reason, guardLocationText()), photos)
            handler.postDelayed({ guard.reaprender() }, 3_000L)
        }
    }

    private fun guardLocationText(): String {
        val point = UbicacionS40.leer(this) ?: return ""
        return "\n" + getString(
            R.string.guard_telegram_location,
            "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.US, point.latitud, point.longitud)
        )
    }

    /** Varias fotos seguidas (la cámara se abre y cierra en cada una). */
    private fun takeGuardPhotos(count: Int, done: (List<java.io.File>) -> Unit) {
        val photos = mutableListOf<java.io.File>()
        // "Flash" de pantalla: blanco al máximo ilumina la cara de enfrente.
        val flash = android.view.View(this).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        (window.decorView as android.view.ViewGroup).addView(flash)
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        fun next(left: Int) {
            if (left == 0) {
                (window.decorView as android.view.ViewGroup).removeView(flash)
                wakeScreen()
                done(photos)
                return
            }
            CamaraFrontal.tomarFoto(this) { file ->
                if (file != null) photos += file
                handler.postDelayed({ next(left - 1) }, GUARD_PHOTO_GAP_MS)
            }
        }
        next(count)
    }

    /** Probar sin alarma: una foto y un mensaje de prueba a Telegram. */
    private fun testGuard() = withCamera {
        if (!Telegram.configurado) {
            Toast.makeText(this, R.string.guard_no_telegram, Toast.LENGTH_LONG).show()
        }
        Toast.makeText(this, R.string.guard_testing, Toast.LENGTH_SHORT).show()
        takeGuardPhotos(1) { photos ->
            if (!Telegram.configurado) {
                Toast.makeText(this, getString(R.string.guard_test_photo_only, photos.size), Toast.LENGTH_LONG).show()
                return@takeGuardPhotos
            }
            Telegram.enviar(this, getString(R.string.guard_telegram_test), photos) { ok, why ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) getString(R.string.guard_test_ok) else getString(R.string.guard_test_failed, why),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** MODE → HISTORIAL: los últimos recorridos guardados en este S40. */
    private fun showHistory() {
        val rides = OdometerStore(this).use { it.rides(HISTORY_SIZE) }
        val dateFormat = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        val rows = if (rides.isEmpty()) {
            arrayOf(getString(R.string.mode_ride_none))
        } else {
            rides.map { ride ->
                getString(
                    R.string.history_row,
                    dateFormat.format(java.util.Date(ride.endedAtMs)),
                    ride.meters / 1000.0,
                    ride.seconds / 3600, (ride.seconds / 60) % 60, ride.seconds % 60,
                    ride.maxKmh.toInt(), ride.averageKmh.toInt(),
                    ride.stops, ride.stoppedSeconds / 60, ride.stoppedSeconds % 60
                )
            }.toTypedArray()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.history_title)
            .setItems(rows, null)
            .setPositiveButton(R.string.mode_close, null)
            .show()
    }

    /** Alarma de posible caída (la decide el Motorola). Cualquier toque = "ESTOY BIEN". */
    private val fallAlarm by lazy { Timbre(this) }
    private var fallWakeLock: android.os.PowerManager.WakeLock? = null

    private fun ringFall(fall: Mensaje.Caida) {
        Bitacora.anotar(this, "ALARMA_CAIDA", fall.segundos.toDouble(), "prueba=${fall.prueba}")
        fallAlarm.sonar(fall.segundos * 1000L)
        if (fallWakeLock == null) {
            @Suppress("DEPRECATION")
            fallWakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Rodada:caida"
            ).apply { acquire(fall.segundos * 1000L + 5_000L) }
        }
        findViewById<LedTextView>(R.id.notice_app).text = if (fall.prueba) getString(R.string.fall_test) else ""
        findViewById<LedTextView>(R.id.notice_title).setText(R.string.fall_title)
        findViewById<LedTextView>(R.id.notice_text).setText(R.string.fall_touch)
        findViewById<MusicWheelView>(R.id.music_wheel).callMode = MusicWheelView.CallMode.RINGING
        showPage(Page.NOTICES)
        wakeScreen()
    }

    /** Botón rojo: avisar ya al contacto, sin esperar la cuenta. */
    private fun askForHelp() {
        Bitacora.anotar(this, "AYUDA")
        if (enlace?.enviar(Mensaje.CaidaAyuda) != true) {
            Toast.makeText(this, R.string.motorola_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        findViewById<LedTextView>(R.id.notice_text).setText(R.string.fall_helping)
    }

    /** La rueda vuelve a música, o a los botones de la llamada si hay una. */
    private fun restoreWheel() {
        findViewById<MusicWheelView>(R.id.music_wheel).callMode = when {
            currentCall == null -> MusicWheelView.CallMode.NONE
            callActive -> MusicWheelView.CallMode.ACTIVE
            else -> MusicWheelView.CallMode.RINGING
        }
    }

    private fun stopFall(tellMotorola: Boolean) {
        val wasRinging = fallAlarm.sonando
        fallAlarm.detener()
        fallWakeLock?.let { if (it.isHeld) it.release() }
        fallWakeLock = null
        restoreWheel()
        if (tellMotorola && wasRinging) {
            Bitacora.anotar(this, "ESTOY_BIEN")
            enlace?.enviar(Mensaje.CaidaOk)
        }
        if (wasRinging && page == Page.NOTICES && currentCall == null) showPage(Page.CENTER)
    }

    private fun fallFinished(result: Mensaje.CaidaAviso) {
        Bitacora.anotar(this, "AVISO_CAIDA", if (result.enviado) 1.0 else 0.0)
        stopFall(tellMotorola = false)
        findViewById<LedTextView>(R.id.notice_app).text = ""
        findViewById<LedTextView>(R.id.notice_title).setText(
            if (result.enviado) R.string.fall_sent else R.string.fall_not_sent
        )
        findViewById<LedTextView>(R.id.notice_text).text = ""
        showPage(Page.NOTICES)
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
        setBrightness(brightLevel())
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
        updateNavViews()
        // "Recalculando…", "Buscando GPS…": se muestran, pero no son maniobra nueva.
        if (NAV_STATUS.containsMatchIn(nav.instruccion)) {
            Bitacora.anotar(this, "NAV_ESTADO")
            return
        }
        val maneuverChanged = nav.instruccion != lastManeuver
        lastManeuver = nav.instruccion
        if (!maneuverChanged) return
        Bitacora.anotar(this, "NAV_MANIOBRA")
        val now = SystemClock.elapsedRealtime()
        val canBuzz = now - lastNavBuzzMs >= NAV_BUZZ_MIN_MS
        if (canBuzz) lastNavBuzzMs = now
        // Maniobra nueva: salta al mapa un rato (salvo si estás leyendo una notificación).
        if (page != Page.NOTICES) {
            showPage(Page.MAP)
            handler.postDelayed(hideNotice, NOTICE_MS)
        }
        wakeScreen()
        if (canBuzz && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
        }
    }

    private var lastNavBuzzMs = 0L

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

    /**
     * Contra la imagen marcada: cada [PIXEL_SHIFT_MS] toda la pantalla se corre unos
     * píxeles siguiendo un ciclo alrededor del centro, así nada queda siempre en el
     * mismo píxel. Es tan poco que no se nota y los toques siguen a la vista.
     */
    private var pixelShiftStep = 0
    private val shiftPixels = object : Runnable {
        override fun run() {
            pixelShiftStep = (pixelShiftStep + 1) % PIXEL_SHIFT_PATTERN.size
            val (dx, dy) = PIXEL_SHIFT_PATTERN[pixelShiftStep]
            val step = PIXEL_SHIFT_DP * resources.displayMetrics.density
            findViewById<android.view.View>(R.id.root).apply {
                translationX = dx * step
                translationY = dy * step
            }
            handler.postDelayed(this, PIXEL_SHIFT_MS)
        }
    }

    /** Calor (sol en el manubrio): aviso y brillo máximo más bajo hasta que enfríe. */
    private var overheated = false
    private var overheatWarnedHot = false
    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val tenths = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) onTemperature(tenths / 10f)
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            if (level >= 0 && (lastLoggedBattery < 0 || kotlin.math.abs(level - lastLoggedBattery) >= 5)) {
                lastLoggedBattery = level
                Bitacora.anotar(this@MainActivity, "BATERIA", level.toDouble())
            }
        }
    }

    private var lastLoggedTemp = Int.MIN_VALUE
    private var lastLoggedBattery = -1

    private fun onTemperature(celsius: Float) {
        showHeatBorder(celsius)
        if (celsius.toInt() != lastLoggedTemp) {
            lastLoggedTemp = celsius.toInt()
            Bitacora.anotar(this, "TEMP", celsius.toDouble())
        }
        when {
            !overheated && celsius >= HOT_C -> {
                overheated = true
                ticker.mostrarUnaVez(getString(R.string.heat_warning, celsius.toInt()))
                say(getString(R.string.speech_heat))
                // Si está brillante, baja ya al brillo de calor.
                if (window.attributes.screenBrightness != DIM_BRIGHTNESS) setBrightness(brightLevel())
            }
            overheated && !overheatWarnedHot && celsius >= VERY_HOT_C -> {
                overheatWarnedHot = true
                ticker.mostrarUnaVez(getString(R.string.heat_warning, celsius.toInt()))
                say(getString(R.string.speech_very_hot))
            }
            overheated && celsius <= COOL_C -> {
                overheated = false
                overheatWarnedHot = false
            }
        }
    }

    /** Marco tipo semáforo: verde < 40 °C, amarillo 40–45 °C, rojo ≥ 45 °C. */
    private var heatColor = 0
    private fun showHeatBorder(celsius: Float) {
        val color = getColor(
            when {
                celsius >= HOT_C -> R.color.heat_red
                celsius >= WARM_C -> R.color.heat_yellow
                else -> R.color.heat_green
            }
        )
        if (color == heatColor) return
        heatColor = color
        val density = resources.displayMetrics.density
        findViewById<android.view.View>(R.id.heat_border).background =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f * density
                setStroke((HEAT_BORDER_DP * density).toInt(), color)
                setColor(android.graphics.Color.TRANSPARENT)
            }
    }

    private fun brightLevel(): Float =
        if (overheated) HOT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL

    /** Brillo al máximo un rato; luego vuelve a tenue. Toque, notificación o maniobra. */
    private fun wakeScreen() {
        setBrightness(brightLevel())
        handler.removeCallbacks(dimScreen)
        val ms = brightMs()
        if (ms > 0L) handler.postDelayed(dimScreen, ms)
    }

    /** Tiempo sin tocar antes de bajar el brillo (MODE); 0 = nunca. */
    private fun brightMs(): Long =
        prefs.getLong(KEY_DIM_MS, DEFAULT_BRIGHT_MS).takeIf { it in DIM_OPTIONS_MS } ?: DEFAULT_BRIGHT_MS

    private fun dimLabel(ms: Long): String = when {
        ms == 0L -> getString(R.string.mode_dim_never)
        ms < 60_000L -> getString(R.string.mode_dim_seconds, ms / 1000)
        else -> getString(R.string.mode_dim_minutes, ms / 60_000)
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
        applyLcdStyle()
    }

    /** Estilo aplicado: noche ámbar, o de día el claro (brillante) / el tenue. */
    private var lcdStyleNight: Boolean? = null
    private var lcdStyleBacklit: Boolean? = null

    /**
     * De noche (según el sol): LED ámbar sobre negro, con brillo alto o tenue.
     * De día: el LCD de siempre, celeste con brillo alto y verdoso al bajar el brillo.
     */
    private fun applyLcdStyle() {
        val night = isDay == false
        val backlit = lcdBacklit ?: false
        if (lcdStyleNight == night && lcdStyleBacklit == backlit) return
        lcdStyleNight = night
        lcdStyleBacklit = backlit
        findViewById<android.view.View>(R.id.info_panel).setBackgroundResource(
            when {
                night -> R.drawable.info_panel_night
                backlit -> R.drawable.info_panel_background
                else -> R.drawable.info_panel_day
            }
        )
        findViewById<android.view.View>(R.id.ticker).setBackgroundResource(
            when {
                night -> R.drawable.ticker_night
                backlit -> R.drawable.ticker_background
                else -> R.drawable.ticker_day
            }
        )
        findViewById<android.view.View>(R.id.notice).setBackgroundResource(
            when {
                night -> R.drawable.notice_night
                backlit -> R.drawable.notice_background
                else -> R.drawable.notice_day
            }
        )
        val nightInk = getColor(R.color.lcd_night_ink)
        val nightGhost = getColor(R.color.lcd_night_ghost)
        fun update(view: android.view.View) {
            when (view) {
                is LedTextView -> if (night) view.setColors(nightInk, nightGhost) else view.setBacklit(backlit)
                is LedTickerView -> if (night) view.setColors(nightInk, nightGhost) else view.setBacklit(backlit)
                is NavArrowView -> if (night) view.setColors(nightInk) else view.setBacklit(backlit)
                is android.view.ViewGroup -> for (index in 0 until view.childCount) update(view.getChildAt(index))
            }
        }
        update(findViewById(R.id.screen_half))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (guard.estado != Guardia.Estado.APAGADO) {
            val wheelArea = android.graphics.Rect()
            findViewById<android.view.View>(R.id.music_wheel).getGlobalVisibleRect(wheelArea)
            if (!wheelArea.contains(event.rawX.toInt(), event.rawY.toInt())) return true
        }
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
                targetStatus.setTextColor(warningColor())
            }
            else -> {
                targetStatus.setText(R.string.target_status_motorola_waiting)
                targetStatus.setTextColor(warningColor())
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
        if (requestCode == CAMERA_PERMISSION) {
            val action = pendingCameraAction
            pendingCameraAction = null
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) action?.invoke()
            return
        }
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
        private const val DEFAULT_BRIGHT_MS = 60_000L
        private const val HISTORY_SIZE = 20
        private const val CAMERA_PERMISSION = 77
        private val GUARD_CODE = listOf(
            MusicWheelView.Zone.UP, MusicWheelView.Zone.UP, MusicWheelView.Zone.PREVIOUS, MusicWheelView.Zone.NEXT
        )
        private const val GUARD_CODE_WINDOW_MS = 10_000L
        private const val GUARD_ALARM_MS = 60_000L
        private const val GUARD_PHOTOS = 3
        private const val GUARD_PHOTO_GAP_MS = 500L
        /** Estados de Maps que no son una maniobra (no vibran). */
        private val NAV_STATUS = Regex(
            "recalcul|buscando|gps|se[ñn]al|esperando|calculando|reroute|searching|rerouting",
            RegexOption.IGNORE_CASE
        )
        private const val NAV_BUZZ_MIN_MS = 10_000L
        private const val PIXEL_SHIFT_MS = 2 * 60 * 1000L
        private const val PIXEL_SHIFT_DP = 2f
        private val PIXEL_SHIFT_PATTERN = listOf(
            0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1
        )
        private const val WARM_C = 40f
        private const val HEAT_BORDER_DP = 4f
        private const val HOT_C = 45f
        private const val VERY_HOT_C = 50f
        private const val COOL_C = 42f
        private const val HOT_BRIGHTNESS = 0.5f
        private const val WINDY_KMH = 20
        private const val TRIPLE_TAP_WAIT_MS = 300L
        private const val WEATHER_FRESH_MS = 30 * 60 * 1000L
        private const val WEATHER_WAIT_MS = 20_000L
        private const val RAIN_SOON_MIN = 180
        private const val SUNSET_SOON_MIN = 60L
        private const val FLOOD_FIX_MAX_AGE_MS = 2 * 60 * 1000L
        private const val FLOOD_FIX_MAX_METERS = 50f
        private val DIM_OPTIONS_MS = listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 0L)
        private const val KEY_DIM_MS = "bajar_brillo_ms"
        private const val DIM_BRIGHTNESS = 0.06f
        private const val MAX_NOTICES = 5
        private const val SWIPE_MIN_PX = 60
        private const val COLOR_OFF = 0xFF9E9E9E.toInt()
        private const val COLOR_NORMAL = 0xFFFFFFFF.toInt()
        private const val COLOR_NORMAL_DAY = 0xFF25292B.toInt()
        private const val COLOR_WARNING_DAY = 0xFFB26A00.toInt()
        private const val DAY_NIGHT_CHECK_MS = 60_000L
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
