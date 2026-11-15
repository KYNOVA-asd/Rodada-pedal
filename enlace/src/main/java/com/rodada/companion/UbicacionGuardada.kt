package com.rodada.companion

import android.content.Context
import com.rodada.enlace.Mensaje

/** Última posición GPS enviada por el S40; solo vive en el Motorola. */
object UbicacionGuardada {
    data class Punto(
        val latitud: Double,
        val longitud: Double,
        val horaMs: Long,
        val precisionMetros: Float
    )

    fun leer(context: Context): Punto? {
        val datos = context.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
        if (!datos.contains(HORA)) return null
        val punto = Punto(
            Double.fromBits(datos.getLong(LATITUD, 0L)),
            Double.fromBits(datos.getLong(LONGITUD, 0L)),
            datos.getLong(HORA, 0L),
            datos.getFloat(PRECISION, -1f)
        )
        return punto.takeIf { valido(it) }
    }

    fun guardar(context: Context, mensaje: Mensaje.Ubicacion) {
        val punto = Punto(mensaje.latitud, mensaje.longitud, mensaje.horaMs, mensaje.precisionMetros)
        if (!valido(punto)) return
        val anterior = leer(context)
        if (anterior != null && punto.horaMs < anterior.horaMs &&
            !(anterior.horaMs - punto.horaMs <= 30_000L &&
                punto.precisionMetros < anterior.precisionMetros)
        ) return
        context.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE).edit()
            .putLong(LATITUD, punto.latitud.toBits())
            .putLong(LONGITUD, punto.longitud.toBits())
            .putLong(HORA, punto.horaMs)
            .putFloat(PRECISION, punto.precisionMetros)
            .apply()
    }

    private fun valido(punto: Punto): Boolean =
        punto.latitud.isFinite() && punto.latitud in -90.0..90.0 &&
            punto.longitud.isFinite() && punto.longitud in -180.0..180.0 &&
            punto.horaMs > 0L && punto.precisionMetros.isFinite() &&
            punto.precisionMetros in 0f..500f

    private const val ARCHIVO = "ubicacion_s40"
    private const val LATITUD = "latitud_bits"
    private const val LONGITUD = "longitud_bits"
    private const val HORA = "hora_ms"
    private const val PRECISION = "precision_metros"
}
