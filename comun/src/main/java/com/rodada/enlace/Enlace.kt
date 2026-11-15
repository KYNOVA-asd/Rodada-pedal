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
 * - NAV\tdistancia\tinstruccion\tresumen
 *                              Motorola → S40: indicación de navegación de Google
 *                              Maps: distancia al giro ("200 m", puede ir vacía),
 *                              instrucción ("Gira a la derecha…") y resumen
 *                              ("3 min · 800 m · Llegada: 5:35 p. m."). Se manda
 *                              cuando cambia y al conectar (versión 3).
 * - NAV_FIN                    Motorola → S40: terminó la navegación (versión 3).
 * - LLAMADA\tid\tapp\tnombre  Motorola → S40: está sonando una llamada entrante
 *                              (Teléfono, WhatsApp…). id identifica la llamada
 *                              (versión 4).
 * - LLAMADA_FIN\tid            Motorola → S40: dejó de sonar (contestada, rechazada
 *                              o colgada) (versión 4).
 * - LLAMADA_CURSO\tid\tapp\tnombre\taltavoz\tsilenciar
 *                              Motorola → S40: la llamada se contestó y está en
 *                              curso. altavoz / silenciar = 1 si su notificación
 *                              tiene ese botón (versión 7).
 * - RESPONDER\tid\taccion      S40 → Motorola: accion es CONTESTAR, RECHAZAR, COLGAR,
 *                              ALTAVOZ o SILENCIAR; el Motorola aprieta ese botón
 *                              de la notificación de la llamada (versión 7).
 * - BUSCAR                     Ambos lados: "buscar mi teléfono", el otro suena
 *                              fuerte unos segundos (versión 8).
 * - BUSCAR_FIN                 Ambos lados: dejar de sonar (versión 8).
 * - UBICACION\tlatitud\tlongitud\thoraMs\tprecisionMetros
 *                              S40 → Motorola: posición GPS del recorrido
 *                              (versión 9). Se conserva solo el último punto.
 * - PEDIR_UBICACION           Motorola → S40: solicita un punto reciente;
 *                              lectura GPS única solo si Rodada está visible
 *                              o ya hay recorrido activo (versión 10).
 * - RECORRIDO\tactivo          S40 → Motorola: 1 al iniciar el recorrido, 0 al
 *                              terminar, y el estado actual al conectar. Mientras
 *                              es 1 el Motorola vigila caídas (versión 12).
 * - FRENON                     S40 → Motorola: el GPS pasó de rápido a 0 de golpe
 *                              (versión 12).
 * - CAIDA\tsegundos\tprueba    Motorola → S40: posible caída; suena la alarma esos
 *                              segundos antes de avisar al contacto. prueba = 1 si
 *                              es la prueba y no se avisará a nadie (versión 12).
 * - CAIDA_OK                   Ambos lados: "ESTOY BIEN", se cancela (versión 12).
 * - CAIDA_AYUDA                S40 → Motorola: "necesito ayuda", avisar ya sin
 *                              esperar la cuenta (versión 12).
 * - CAIDA_AVISO\tenviado        Motorola → S40: terminó la cuenta; enviado = 1 si
 *                              se mandó el SMS y la llamada (versión 12).
 *
 * Privacidad: el contenido de NOTIF, DECIR y NAV no se guarda en disco ni en logs.
 */
object Enlace {
    val UUID: UUID = java.util.UUID.fromString("6f1c2a4e-8d3b-4c55-9a1e-52d0a7c3e901")
    const val NOMBRE_SERVICIO = "Rodada Enlace"
    const val VERSION = 12
    const val MAX_LINEA = 2048
    const val MAX_APP = 40
    const val MAX_TITULO = 80
    const val MAX_TEXTO = 240

    fun recortar(texto: String, max: Int): String =
        if (texto.length <= max) texto else texto.take(max - 1).trimEnd() + "…"
}

sealed class Mensaje {
    data class Hola(val version: Int, val nombre: String) : Mensaje()
    data class Ubicacion(val latitud: Double, val longitud: Double, val horaMs: Long, val precisionMetros: Float) : Mensaje()
    object PedirUbicacion : Mensaje()
    data class Decir(val texto: String) : Mensaje()
    data class Notif(val app: String, val titulo: String, val texto: String) : Mensaje()
    data class Cancion(val estado: Estado, val artista: String, val titulo: String) : Mensaje() {
        enum class Estado { REPRODUCIENDO, PAUSA, NADA }
    }
    data class Nav(val distancia: String, val instruccion: String, val resumen: String) : Mensaje()
    object NavFin : Mensaje()
    data class Llamada(val id: String, val app: String, val nombre: String) : Mensaje()
    data class LlamadaFin(val id: String) : Mensaje()
    data class LlamadaEnCurso(
        val id: String,
        val app: String,
        val nombre: String,
        val altavoz: Boolean,
        val silenciar: Boolean
    ) : Mensaje()
    object Buscar : Mensaje()
    object BuscarFin : Mensaje()
    data class Recorrido(val activo: Boolean) : Mensaje()
    object Frenon : Mensaje()
    data class Caida(val segundos: Int, val prueba: Boolean) : Mensaje()
    object CaidaOk : Mensaje()
    object CaidaAyuda : Mensaje()
    data class CaidaAviso(val enviado: Boolean) : Mensaje()
    data class Responder(val id: String, val accion: Accion) : Mensaje() {
        enum class Accion { CONTESTAR, RECHAZAR, COLGAR, ALTAVOZ, SILENCIAR }
    }

    /** Línea lista para enviar, con el '\n' final. */
    fun codificar(): String {
        val campos = when (this) {
            is Hola -> listOf("HOLA", version.toString(), nombre)
            is Ubicacion -> listOf("UBICACION", latitud.toString(), longitud.toString(), horaMs.toString(), precisionMetros.toString())
            PedirUbicacion -> listOf("PEDIR_UBICACION")
            is Decir -> listOf("DECIR", texto)
            is Notif -> listOf("NOTIF", app, titulo, texto)
            is Cancion -> listOf("CANCION", estado.name, artista, titulo)
            is Nav -> listOf("NAV", distancia, instruccion, resumen)
            NavFin -> listOf("NAV_FIN")
            is Llamada -> listOf("LLAMADA", id, app, nombre)
            is LlamadaFin -> listOf("LLAMADA_FIN", id)
            is LlamadaEnCurso -> listOf("LLAMADA_CURSO", id, app, nombre, if (altavoz) "1" else "0", if (silenciar) "1" else "0")
            is Responder -> listOf("RESPONDER", id, accion.name)
            Buscar -> listOf("BUSCAR")
            BuscarFin -> listOf("BUSCAR_FIN")
            is Recorrido -> listOf("RECORRIDO", if (activo) "1" else "0")
            Frenon -> listOf("FRENON")
            is Caida -> listOf("CAIDA", segundos.toString(), if (prueba) "1" else "0")
            CaidaOk -> listOf("CAIDA_OK")
            CaidaAyuda -> listOf("CAIDA_AYUDA")
            is CaidaAviso -> listOf("CAIDA_AVISO", if (enviado) "1" else "0")
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
                "UBICACION" -> {
                    val lat = campos.getOrNull(1)?.toDoubleOrNull()
                    val lon = campos.getOrNull(2)?.toDoubleOrNull()
                    val hora = campos.getOrNull(3)?.toLongOrNull()
                    val precision = campos.getOrNull(4)?.toFloatOrNull()
                    if (campos.size == 5 && lat != null && lon != null && hora != null && precision != null &&
                        lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0 &&
                        hora > 0L && precision.isFinite() && precision in 0f..500f
                    ) Ubicacion(lat, lon, hora, precision) else null
                }
                "PEDIR_UBICACION" -> if (campos.size == 1) PedirUbicacion else null
                "DECIR" -> if (campos.size >= 2) Decir(campos[1]) else null
                "NOTIF" -> if (campos.size >= 4) Notif(campos[1], campos[2], campos[3]) else null
                "CANCION" -> {
                    val estado = Cancion.Estado.entries.firstOrNull { it.name == campos.getOrNull(1) }
                    if (campos.size >= 4 && estado != null) Cancion(estado, campos[2], campos[3]) else null
                }
                "NAV" -> if (campos.size >= 3) Nav(campos[1], campos[2], campos.getOrElse(3) { "" }) else null
                "NAV_FIN" -> NavFin
                "LLAMADA" -> if (campos.size >= 4) Llamada(campos[1], campos[2], campos[3]) else null
                "LLAMADA_FIN" -> if (campos.size >= 2) LlamadaFin(campos[1]) else null
                "BUSCAR" -> Buscar
                "BUSCAR_FIN" -> BuscarFin
                "RECORRIDO" -> if (campos.size >= 2) Recorrido(campos[1] == "1") else null
                "FRENON" -> Frenon
                "CAIDA" -> {
                    val segundos = campos.getOrNull(1)?.toIntOrNull()
                    if (segundos != null && segundos in 1..600) Caida(segundos, campos.getOrNull(2) == "1") else null
                }
                "CAIDA_OK" -> CaidaOk
                "CAIDA_AYUDA" -> CaidaAyuda
                "CAIDA_AVISO" -> if (campos.size >= 2) CaidaAviso(campos[1] == "1") else null
                "LLAMADA_CURSO" -> if (campos.size >= 5) {
                    LlamadaEnCurso(campos[1], campos[2], campos[3], campos[4] == "1", campos.getOrElse(5) { "0" } == "1")
                } else null
                "RESPONDER" -> {
                    val accion = Responder.Accion.entries.firstOrNull { it.name == campos.getOrNull(2) }
                    if (campos.size >= 3 && accion != null) Responder(campos[1], accion) else null
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
