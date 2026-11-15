package com.rodada.app

import android.content.Context
import android.util.Log
import com.rodada.enlace.Bitacora
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Alertas del modo guardia por Telegram (lo único que el S40 manda a internet).
 * El token va en .env (telegram_bot), nunca en logs. El chat se toma de
 * telegram_chat en .env o, si no está, del último /start que recibió el bot.
 */
internal object Telegram {
    private const val TAG = "RodadaGuardia"
    private const val PREFS = "telegram"
    private const val CHAT = "chat_id"

    val configurado: Boolean get() = BuildConfig.TELEGRAM_BOT.isNotBlank()

    /** En un hilo aparte; alTerminar(ok, motivo) en ese hilo. */
    fun enviar(context: Context, texto: String, fotos: List<File>, alTerminar: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!configurado) {
            alTerminar(false, "sin token")
            return
        }
        Thread({
            val resultado = try {
                val chat = chat(context) ?: throw IllegalStateException("sin chat: mándale /start a tu bot")
                if (fotos.isEmpty()) {
                    post("sendMessage", mapOf("chat_id" to chat, "text" to texto))
                } else {
                    fotos.forEachIndexed { i, foto ->
                        enviarFoto(chat, foto, if (i == 0) texto else "")
                    }
                }
                true to "ok"
            } catch (e: Exception) {
                val motivo = if (e is java.io.IOException || e is IllegalStateException) e.message.orEmpty() else e.javaClass.simpleName
                Log.w(TAG, "telegram falló: $motivo")
                false to motivo
            }
            Bitacora.anotar(context, if (resultado.first) "TELEGRAM_OK" else "TELEGRAM_ERROR", fotos.size.toDouble(), resultado.second)
            alTerminar(resultado.first, resultado.second)
        }, "RodadaTelegram").start()
    }

    private fun chat(context: Context): String? {
        if (BuildConfig.TELEGRAM_CHAT.isNotBlank()) return BuildConfig.TELEGRAM_CHAT
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(CHAT, null)?.let { return it }
        val respuesta = JSONObject(leer(url("getUpdates")))
        val lista = respuesta.optJSONArray("result") ?: return null
        for (i in lista.length() - 1 downTo 0) {
            val mensaje = lista.getJSONObject(i).optJSONObject("message") ?: continue
            val id = mensaje.optJSONObject("chat")?.optLong("id") ?: continue
            prefs.edit().putString(CHAT, id.toString()).apply()
            return id.toString()
        }
        return null
    }

    private fun url(metodo: String) = "https://api.telegram.org/bot${BuildConfig.TELEGRAM_BOT}/$metodo"

    private fun leer(direccion: String): String {
        val conexion = URL(direccion).openConnection() as HttpURLConnection
        conexion.connectTimeout = 15_000
        conexion.readTimeout = 15_000
        try {
            if (conexion.responseCode != 200) throw java.io.IOException("http ${conexion.responseCode}")
            return conexion.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexion.disconnect()
        }
    }

    private fun post(metodo: String, campos: Map<String, String>) {
        val cuerpo = campos.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val conexion = URL(url(metodo)).openConnection() as HttpURLConnection
        conexion.connectTimeout = 15_000
        conexion.readTimeout = 15_000
        conexion.requestMethod = "POST"
        conexion.doOutput = true
        conexion.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conexion.outputStream.use { it.write(cuerpo.toByteArray()) }
            if (conexion.responseCode != 200) throw java.io.IOException("http ${conexion.responseCode}")
        } finally {
            conexion.disconnect()
        }
    }

    private fun enviarFoto(chat: String, foto: File, texto: String) {
        val limite = "----Rodada${System.currentTimeMillis()}"
        val conexion = URL(url("sendPhoto")).openConnection() as HttpURLConnection
        conexion.connectTimeout = 15_000
        conexion.readTimeout = 30_000
        conexion.requestMethod = "POST"
        conexion.doOutput = true
        conexion.setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
        try {
            conexion.outputStream.use { salida ->
                fun campo(nombre: String, valor: String) {
                    salida.write("--$limite\r\nContent-Disposition: form-data; name=\"$nombre\"\r\n\r\n$valor\r\n".toByteArray())
                }
                campo("chat_id", chat)
                if (texto.isNotEmpty()) campo("caption", texto)
                salida.write(
                    "--$limite\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"${foto.name}\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()
                )
                foto.inputStream().use { it.copyTo(salida) }
                salida.write("\r\n--$limite--\r\n".toByteArray())
            }
            if (conexion.responseCode != 200) throw java.io.IOException("http ${conexion.responseCode}")
        } finally {
            conexion.disconnect()
        }
    }
}
