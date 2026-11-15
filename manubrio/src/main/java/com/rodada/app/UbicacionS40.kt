package com.rodada.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rodada.enlace.Mensaje

/** Comparte el GPS del recorrido o una única lectura solicitada con Rodada visible. */
internal object UbicacionS40 {
    private const val ARCHIVO = "ultima_ubicacion"
    private const val INTERVALO_MS = 10_000L
    /** El GPS del S40 tarda minutos en arrancar en frío; con 30 s nunca se ubicaba. */
    private const val PLAZO_MS = 120_000L
    private const val QUIEN = "lectura"
    private val principal = Handler(Looper.getMainLooper())
    private var ultimoGuardadoMs = 0L
    private var ultima: Mensaje.Ubicacion? = null
    private var pendienteRecorrido = false
    private var lecturaUnica: LocationListener? = null
    private var gestor: LocationManager? = null
    private var lecturaRed: CancellationSignal? = null
    private var enviadaEnSolicitud = false
    private var vencimiento: Runnable? = null

    /** No inicia GPS en segundo plano: si Rodada no está visible devuelve el último punto. */
    fun solicitar(context: Context, rodadaVisible: Boolean) {
        cancelarSolicitud()
        val ultimo = leer(context)
        val reciente = ultimo != null &&
            (System.currentTimeMillis() - ultimo.horaMs) in 0L..20_000L
        if (reciente) {
            EnlaceCliente.enviarUbicacion(ultimo!!)
            return
        }
        if (RideState.running) {
            pendienteRecorrido = true
            programarVencimiento(context.applicationContext)
            return
        }
        if (!rodadaVisible ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            ultimo?.let { EnlaceCliente.enviarUbicacion(it) }
            return
        }
        val gps = context.getSystemService(LocationManager::class.java)
        if (!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            ultimo?.let { EnlaceCliente.enviarUbicacion(it) }
            return
        }
        // Se escucha el GPS hasta la primera lectura buena o hasta PLAZO_MS.
        val oyente = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (lecturaUnica !== this) return
                if (location.hasAccuracy() && location.accuracy <= 25f) {
                    cancelarSolicitud()
                    actualizar(context.applicationContext, location, forzar = true)
                }
            }

            override fun onProviderDisabled(provider: String) {
                if (lecturaUnica !== this) return
                val enviada = enviadaEnSolicitud
                cancelarSolicitud()
                if (!enviada) ultimo?.let { EnlaceCliente.enviarUbicacion(it) }
            }
        }
        lecturaUnica = oyente
        gestor = gps
        programarVencimiento(context.applicationContext)
        try {
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, oyente, Looper.getMainLooper())
            Satelites.empezar(gps, QUIEN)
            // Dentro de casa puede llegar primero una posición aproximada de Wi-Fi/red.
            if (lecturaUnica != null && gps.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val senalRed = CancellationSignal()
                lecturaRed = senalRed
                gps.getCurrentLocation(LocationManager.NETWORK_PROVIDER, senalRed, context.mainExecutor) { location ->
                    if (lecturaRed !== senalRed) return@getCurrentLocation
                    lecturaRed = null
                    if (location != null && location.hasAccuracy() && location.accuracy <= 500f) {
                        enviadaEnSolicitud = true
                        actualizar(context.applicationContext, location, forzar = true, maxPrecision = 500f)
                    } else if (lecturaUnica == null) {
                        val enviada = enviadaEnSolicitud
                        cancelarSolicitud()
                        if (!enviada) ultimo?.let { EnlaceCliente.enviarUbicacion(it) }
                    }
                }
            }
        } catch (_: SecurityException) {
            cancelarSolicitud()
            ultimo?.let { EnlaceCliente.enviarUbicacion(it) }
        } catch (_: IllegalArgumentException) {
            cancelarSolicitud()
            ultimo?.let { EnlaceCliente.enviarUbicacion(it) }
        }
    }

    private fun programarVencimiento(context: Context) {
        val tarea = Runnable {
            val enviada = enviadaEnSolicitud
            cancelarSolicitud()
            if (!enviada) leer(context)?.let { EnlaceCliente.enviarUbicacion(it) }
        }
        vencimiento = tarea
        principal.postDelayed(tarea, PLAZO_MS)
    }

    private fun cancelarSolicitud() {
        vencimiento?.let { principal.removeCallbacks(it) }
        vencimiento = null
        lecturaUnica?.let { gestor?.removeUpdates(it) }
        lecturaUnica = null
        gestor?.let { Satelites.terminar(QUIEN) }
        gestor = null
        lecturaRed?.cancel()
        lecturaRed = null
        enviadaEnSolicitud = false
        pendienteRecorrido = false
    }

    fun actualizar(context: Context, location: Location, forzar: Boolean = false, maxPrecision: Float = 25f) {
        if (!location.hasAccuracy() || location.accuracy > maxPrecision || location.time <= 0L) return
        val punto = Mensaje.Ubicacion(location.latitude, location.longitude, location.time, location.accuracy)
        ultima = punto
        val ahora = SystemClock.elapsedRealtime()
        val responder = pendienteRecorrido
        if (responder) cancelarSolicitud()
        if (!forzar && !responder && ahora - ultimoGuardadoMs < INTERVALO_MS) return
        context.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit()
            .putLong("latitud", punto.latitud.toBits())
            .putLong("longitud", punto.longitud.toBits())
            .putLong("hora", punto.horaMs)
            .putFloat("precision", punto.precisionMetros)
            .apply()
        ultimoGuardadoMs = ahora
        EnlaceCliente.enviarUbicacion(punto)
    }

    fun leer(context: Context): Mensaje.Ubicacion? {
        ultima?.let { return it }
        val datos = context.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        if (!datos.contains("hora")) return null
        val punto = Mensaje.Ubicacion(
            Double.fromBits(datos.getLong("latitud", 0L)),
            Double.fromBits(datos.getLong("longitud", 0L)),
            datos.getLong("hora", 0L),
            datos.getFloat("precision", -1f)
        )
        return punto.takeIf {
            it.latitud.isFinite() && it.latitud in -90.0..90.0 &&
                it.longitud.isFinite() && it.longitud in -180.0..180.0 &&
                it.horaMs > 0L && it.precisionMetros in 0f..500f
        }?.also { ultima = it }
    }
}
