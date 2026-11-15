package com.rodada.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rodada.enlace.Mensaje
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Clima de OpenWeatherMap, lo único que Rodada Enlace manda a internet. Solo durante
 * un recorrido: al empezar y cada [CADA_MS]; si la última respuesta tiene menos de
 * [CACHE_MS] se reusa. La ubicación (la del S40) va redondeada a ~1 km. Recuerda la
 * hora de la última lluvia vista para saber si "llovió" (encharcamientos).
 */
class Clima(
    context: Context,
    /** Sin ubicación reciente: pedirle una al S40 (PEDIR_UBICACION). */
    private val pedirUbicacion: () -> Unit,
    private val alTener: (Mensaje.Clima) -> Unit
) {
    private val contexto = context.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private val prefs = contexto.getSharedPreferences("clima", Context.MODE_PRIVATE)
    private var activo = false
    private var esperandoUbicacion = false
    private var esperandoAPedido = false
    private var consultando = false
    private var ultimo: Mensaje.Clima? = null
    private var ultimoMs = 0L
    private val periodico = Runnable { consultar() }

    fun iniciarRecorrido() {
        if (activo) return
        activo = true
        consultar()
    }

    fun terminarRecorrido() {
        activo = false
        esperandoUbicacion = false
        esperandoAPedido = false
        principal.removeCallbacks(periodico)
    }

    /** Llegó una ubicación del S40: si se esperaba una para consultar, ahora sí. */
    fun nuevaUbicacion() {
        if (esperandoUbicacion) consultar(aPedido = esperandoAPedido)
    }

    /** Tres toques en el S40: consulta ahora aunque no haya recorrido. */
    fun pedirAhora() = consultar(aPedido = true)

    /** La última respuesta si tiene menos de una hora (para mandarla al reconectar). */
    fun reciente(): Mensaje.Clima? = ultimo?.takeIf { System.currentTimeMillis() - ultimoMs < CADA_MS }

    private fun consultar(aPedido: Boolean = false) {
        if (!activo && !aPedido) return
        if (activo) {
            principal.removeCallbacks(periodico)
            principal.postDelayed(periodico, CADA_MS)
        }
        val ahora = System.currentTimeMillis()
        ultimo?.let { if (ahora - ultimoMs < CACHE_MS) { alTener(it); return } }
        if (BuildConfig.API_CLIMA.isBlank() || consultando) return
        // A pedido sirve una ubicación más vieja: para el clima basta la zona.
        val edadMax = if (aPedido) UBICACION_A_PEDIDO_MS else UBICACION_MAX_MS
        val punto = UbicacionGuardada.leer(contexto)?.takeIf { ahora - it.horaMs < edadMax }
        if (punto == null) {
            esperandoUbicacion = true
            esperandoAPedido = aPedido
            pedirUbicacion()
            return
        }
        esperandoUbicacion = false
        esperandoAPedido = false
        consultando = true
        // ~1 km: suficiente para el clima y no manda la ubicación exacta.
        val lat = "%.2f".format(Locale.US, punto.latitud)
        val lon = "%.2f".format(Locale.US, punto.longitud)
        Thread({
            val resultado = try {
                pedir(lat, lon)
            } catch (e: Exception) {
                // El mensaje solo trae "http NNN" (nunca la URL con la clave).
                Log.w(TAG, "clima falló: ${e.javaClass.simpleName} ${if (e is java.io.IOException) e.message.orEmpty() else ""}")
                null
            }
            principal.post {
                consultando = false
                if (resultado != null) {
                    ultimo = resultado
                    ultimoMs = System.currentTimeMillis()
                    alTener(resultado)
                }
            }
        }, "RodadaClima").start()
    }

    private fun pedir(lat: String, lon: String): Mensaje.Clima {
        val base = "https://api.openweathermap.org/data/2.5"
        val consulta = "lat=$lat&lon=$lon&units=metric&lang=es&appid=${BuildConfig.API_CLIMA}"
        val actual = JSONObject(leer("$base/weather?$consulta"))
        val pronostico = JSONObject(leer("$base/forecast?$consulta&cnt=3"))
        val ahora = System.currentTimeMillis()

        val tiempo = actual.getJSONArray("weather").getJSONObject(0)
        val lloviendo = esLluvia(tiempo.optInt("id"))
        if (lloviendo) prefs.edit().putLong(ULTIMA_LLUVIA, ahora).apply()
        val llovio = ahora - prefs.getLong(ULTIMA_LLUVIA, 0L) < LLOVIO_MS

        // Primer bloque de 3 h con lluvia probable (≥ 50 %) o lluvia en el pronóstico.
        var lluviaEnMin = if (lloviendo) 0 else -1
        val lista = pronostico.getJSONArray("list")
        for (i in 0 until lista.length()) {
            if (lluviaEnMin >= 0) break
            val bloque = lista.getJSONObject(i)
            val id = bloque.getJSONArray("weather").getJSONObject(0).optInt("id")
            if (bloque.optDouble("pop", 0.0) >= 0.5 || esLluvia(id)) {
                lluviaEnMin = ((bloque.getLong("dt") * 1000L - ahora) / 60_000L).toInt().coerceAtLeast(0)
            }
        }
        return Mensaje.Clima(
            tempC = actual.getJSONObject("main").getDouble("temp").roundToInt(),
            descripcion = tiempo.optString("description"),
            vientoKmh = (actual.optJSONObject("wind")?.optDouble("speed", 0.0) ?: 0.0).times(3.6).roundToInt(),
            llovio = llovio || lloviendo,
            lluviaEnMin = lluviaEnMin,
            puestaSolMs = (actual.optJSONObject("sys")?.optLong("sunset", 0L) ?: 0L) * 1000L
        )
    }

    /** La URL lleva la clave: nunca se registra en el log. */
    private fun leer(url: String): String {
        val conexion = URL(url).openConnection() as HttpURLConnection
        conexion.connectTimeout = 10_000
        conexion.readTimeout = 10_000
        try {
            if (conexion.responseCode != 200) throw java.io.IOException("http ${conexion.responseCode}")
            return conexion.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexion.disconnect()
        }
    }

    /** Códigos de OpenWeatherMap: 2xx tormenta, 3xx llovizna, 5xx lluvia. */
    private fun esLluvia(id: Int) = id in 200..599

    companion object {
        private const val TAG = "RodadaClima"
        private const val ULTIMA_LLUVIA = "ultima_lluvia_ms"
        const val CADA_MS = 60 * 60 * 1000L
        private const val CACHE_MS = 30 * 60 * 1000L
        private const val UBICACION_MAX_MS = 15 * 60 * 1000L
        private const val UBICACION_A_PEDIDO_MS = 12 * 60 * 60 * 1000L
        /** "Llovió" = vio lluvia en las últimas 6 h. */
        private const val LLOVIO_MS = 6 * 60 * 60 * 1000L
    }
}
