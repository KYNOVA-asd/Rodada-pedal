package com.rodada.app

import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.rodada.enlace.Mensaje

/** Aprovecha únicamente los puntos que ya obtiene RideService durante el recorrido. */
internal object UbicacionS40 {
    private const val ARCHIVO = "ultima_ubicacion"
    private const val INTERVALO_MS = 10_000L
    private var ultimoGuardadoMs = 0L
    private var ultima: Mensaje.Ubicacion? = null

    fun actualizar(context: Context, location: Location, forzar: Boolean = false) {
        if (!location.hasAccuracy() || location.accuracy > 25f || location.time <= 0L) return
        val punto = Mensaje.Ubicacion(location.latitude, location.longitude, location.time, location.accuracy)
        ultima = punto
        val ahora = SystemClock.elapsedRealtime()
        if (!forzar && ahora - ultimoGuardadoMs < INTERVALO_MS) return
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
                it.horaMs > 0L && it.precisionMetros in 0f..25f
        }?.also { ultima = it }
    }
}
