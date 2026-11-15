package com.rodada.app

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper

/**
 * Cuántos satélites ve el GPS y cuántos usa, para mostrar "buscando" con datos.
 * Solo escucha mientras alguien tiene el GPS encendido (recorrido o lectura única);
 * no enciende el GPS por sí solo.
 */
internal object Satelites {
    @Volatile var vistos = 0
        private set
    @Volatile var usados = 0
        private set
    private val usuarios = mutableSetOf<String>()
    private var gestor: LocationManager? = null

    private val oyente = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var enUso = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) enUso++
            vistos = status.satelliteCount
            usados = enUso
        }

        override fun onStopped() {
            vistos = 0
            usados = 0
        }
    }

    @SuppressLint("MissingPermission")
    fun empezar(locationManager: LocationManager, quien: String) {
        if (!usuarios.add(quien) || usuarios.size > 1) return
        gestor = locationManager
        try {
            locationManager.registerGnssStatusCallback(oyente, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) {
            usuarios.remove(quien)
        }
    }

    fun terminar(quien: String) {
        if (!usuarios.remove(quien) || usuarios.isNotEmpty()) return
        gestor?.unregisterGnssStatusCallback(oyente)
        gestor = null
        vistos = 0
        usados = 0
    }
}
