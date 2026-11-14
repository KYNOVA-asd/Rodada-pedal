package com.rodada.enlace

import java.util.UUID

/**
 * Protocolo "Rodada Enlace": mensajes de texto entre el S40 (módulo manubrio) y
 * el Motorola (módulo enlace) por Bluetooth clásico (RFCOMM). Vive en el módulo
 * comun, del que dependen los dos; si cambias el formato, sube [VERSION].
 *
 * Conexión
 * - El Motorola escucha con listenUsingRfcommWithServiceRecord([NOMBRE_SERVICIO], [UUID]).
 * - El S40 se conecta con createRfcommSocketToServiceRecord([UUID]) al Motorola
 *   ya emparejado (el mismo emparejamiento que usa el control HID).
 * - Canal cifrado de Bluetooth entre dispositivos emparejados. Nada sale a internet.
 * - Sin mensajes periódicos (no hay ping): la desconexión se detecta cuando
 *   falla la lectura del socket. Así no se gasta batería en sondeos.
 *
 * Formato
 * - Texto UTF-8, un mensaje por línea, terminado en '\n'.
 * - Campos separados por tabulador: TIPO\tcampo1\tcampo2...
 * - Dentro de un campo se escapan '\\' como "\\\\", tabulador como "\\t" y
 *   salto de línea como "\\n".
 * - Una línea de más de [MAX_LINEA] caracteres se descarta.
 * - Un tipo desconocido se ignora (compatibilidad hacia adelante).
 *
 * Mensajes
 * - HOLA\tversion\tnombre      Ambos lados, al conectar. version = [VERSION].
 * - DECIR\ttexto               S40 → Motorola: decir el texto con el TTS del
 *                              Motorola, pausando la música mientras habla.
 * - NOTIF\tapp\ttitulo\ttexto  Motorola → S40: notificación de una app elegida,
 *                              ya recortada a [MAX_APP], [MAX_TITULO] y [MAX_TEXTO].
 * - CANCION\testado\tartista\ttitulo
 *                              Motorola → S40: lo que suena. estado es
 *                              REPRODUCIENDO, PAUSA o NADA. Se manda solo cuando
 *                              cambia y una vez al conectar (versión 2).
 *
 * Privacidad: el contenido de NOTIF y DECIR no se guarda en disco ni en logs.
 */
object Enlace {
    val UUID: UUID = java.util.UUID.fromString("6f1c2a4e-8d3b-4c55-9a1e-52d0a7c3e901")
    const val NOMBRE_SERVICIO = "Rodada Enlace"
    const val VERSION = 2
    const val MAX_LINEA = 2048
    const val MAX_APP = 40
    const val MAX_TITULO = 80
    const val MAX_TEXTO = 240

    fun recortar(texto: String, max: Int): String =
        if (texto.length <= max) texto else texto.take(max - 1).trimEnd() + "…"
}

sealed class Mensaje {
    data class Hola(val version: Int, val nombre: String) : Mensaje()
    data class Decir(val texto: String) : Mensaje()
    data class Notif(val app: String, val titulo: String, val texto: String) : Mensaje()
    data class Cancion(val estado: Estado, val artista: String, val titulo: String) : Mensaje() {
        enum class Estado { REPRODUCIENDO, PAUSA, NADA }
    }

    /** Línea lista para enviar, con el '\n' final. */
    fun codificar(): String {
        val campos = when (this) {
            is Hola -> listOf("HOLA", version.toString(), nombre)
            is Decir -> listOf("DECIR", texto)
            is Notif -> listOf("NOTIF", app, titulo, texto)
            is Cancion -> listOf("CANCION", estado.name, artista, titulo)
        }
        return campos.joinToString("\t") { escapar(it) } + "\n"
    }

    /**
     * Solo el tipo, nunca el contenido, para poder registrarlo sin datos privados.
     * Es final para que las data class no generen su propio toString.
     */
    final override fun toString(): String = javaClass.simpleName

    companion object {
        /** null si la línea es inválida o de un tipo desconocido. */
        fun decodificar(linea: String): Mensaje? {
            if (linea.length > Enlace.MAX_LINEA) return null
            val campos = linea.split('\t').map { desescapar(it) }
            return when (campos[0]) {
                "HOLA" -> if (campos.size >= 3) Hola(campos[1].toIntOrNull() ?: 0, campos[2]) else null
                "DECIR" -> if (campos.size >= 2) Decir(campos[1]) else null
                "NOTIF" -> if (campos.size >= 4) Notif(campos[1], campos[2], campos[3]) else null
                "CANCION" -> {
                    val estado = Cancion.Estado.entries.firstOrNull { it.name == campos.getOrNull(1) }
                    if (campos.size >= 4 && estado != null) Cancion(estado, campos[2], campos[3]) else null
                }
                else -> null
            }
        }

        private fun escapar(campo: String): String =
            campo.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

        private fun desescapar(campo: String): String {
            if ('\\' !in campo) return campo
            val salida = StringBuilder(campo.length)
            var i = 0
            while (i < campo.length) {
                val c = campo[i]
                if (c == '\\' && i + 1 < campo.length) {
                    when (campo[i + 1]) {
                        't' -> salida.append('\t')
                        'n' -> salida.append('\n')
                        else -> salida.append(campo[i + 1])
                    }
                    i += 2
                } else {
                    salida.append(c)
                    i++
                }
            }
            return salida.toString()
        }
    }
}
