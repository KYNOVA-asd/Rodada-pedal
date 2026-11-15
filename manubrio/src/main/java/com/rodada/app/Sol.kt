package com.rodada.app

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Salida y puesta del sol calculadas en el S40 con la última ubicación del GPS
 * (ecuación de salida del sol, error de un par de minutos). Sin internet.
 */
internal object Sol {
    private const val J2000 = 2451545.0
    private const val DIA_MS = 86_400_000.0
    private const val JULIANO_UNIX = 2440587.5

    /** true si en [ms] el sol está arriba en lat/lon. */
    fun esDeDia(latitud: Double, longitud: Double, ms: Long): Boolean {
        val (salida, puesta) = salidaYPuesta(latitud, longitud, ms) ?: return polarDeDia(latitud, ms)
        return ms in salida until puesta
    }

    /** Salida y puesta (ms) del día solar que contiene [ms]; null si es día o noche polar. */
    fun salidaYPuesta(latitud: Double, longitud: Double, ms: Long): Pair<Long, Long>? {
        val juliano = ms / DIA_MS + JULIANO_UNIX
        // Día solar local más cercano (redondeo, no ceil: con longitud oeste, ceil
        // tomaba el sol de mañana por la tarde).
        val n = kotlin.math.round(juliano - J2000 - 0.0009 + longitud / 360.0)
        val medio = n + 0.0009 - longitud / 360.0
        val m = rad((357.5291 + 0.98560028 * medio) % 360.0)
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = rad((Math.toDegrees(m) + c + 180.0 + 102.9372) % 360.0)
        val transito = J2000 + medio + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val declinacion = kotlin.math.asin(sin(lambda) * sin(rad(23.4397)))
        val phi = rad(latitud)
        val cosOmega = (sin(rad(-0.833)) - sin(phi) * sin(declinacion)) / (cos(phi) * cos(declinacion))
        if (cosOmega < -1.0 || cosOmega > 1.0) return null
        val omega = Math.toDegrees(acos(cosOmega)) / 360.0
        return aMs(transito - omega) to aMs(transito + omega)
    }

    private fun polarDeDia(latitud: Double, ms: Long): Boolean {
        val mes = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.MONTH)
        val veranoNorte = mes in 3..8
        return if (latitud >= 0) veranoNorte else !veranoNorte
    }

    private fun aMs(juliano: Double): Long = ((juliano - JULIANO_UNIX) * DIA_MS).toLong()
    private fun rad(grados: Double) = Math.toRadians(grados)
}
