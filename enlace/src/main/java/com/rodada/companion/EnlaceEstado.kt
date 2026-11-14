package com.rodada.companion

/** Estado del enlace compartido entre el servicio y la pantalla (mismo proceso, hilo principal). */
object EnlaceEstado {
    sealed class Estado {
        object Apagado : Estado()
        object Esperando : Estado()
        data class Conectado(val nombre: String) : Estado()
        data class Error(val mensaje: Int) : Estado()
    }

    var estado: Estado = Estado.Apagado
        set(value) {
            field = value
            oyente?.invoke(value)
        }

    var oyente: ((Estado) -> Unit)? = null

    /** El S40 está buscando este teléfono (suena la alarma). */
    var buscando = false
        set(value) {
            field = value
            oyenteBuscando?.invoke(value)
        }

    var oyenteBuscando: ((Boolean) -> Unit)? = null
}
