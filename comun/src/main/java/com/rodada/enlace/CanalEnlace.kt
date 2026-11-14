package com.rodada.enlace

import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/**
 * Un socket RFCOMM ya conectado: lee líneas en un hilo propio (bloqueado en
 * read, sin sondeo) y escribe en otro. Los callbacks llegan en el hilo principal.
 */
class CanalEnlace(
    private val socket: BluetoothSocket,
    private val alRecibir: (Mensaje) -> Unit,
    private val alCerrar: () -> Unit
) {
    private val principal = Handler(Looper.getMainLooper())
    private val escritor = Executors.newSingleThreadExecutor()
    private val salida = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
    @Volatile private var cerrado = false

    fun iniciar() {
        Thread({
            try {
                val lector = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                while (!cerrado) {
                    val linea = lector.readLine() ?: break
                    val mensaje = Mensaje.decodificar(linea) ?: continue
                    principal.post { if (!cerrado) alRecibir(mensaje) }
                }
            } catch (e: IOException) {
                if (!cerrado) Log.d(TAG, "lectura terminó: ${e.javaClass.simpleName}")
            }
            cerrar()
        }, "RodadaEnlaceLector").start()
    }

    fun enviar(mensaje: Mensaje) {
        if (cerrado) return
        escritor.execute {
            try {
                salida.write(mensaje.codificar())
                salida.flush()
                Log.d(TAG, "enviado $mensaje")
            } catch (e: IOException) {
                Log.d(TAG, "escritura falló: ${e.javaClass.simpleName}")
                cerrar()
            }
        }
    }

    fun cerrar() {
        if (cerrado) return
        cerrado = true
        try {
            socket.close()
        } catch (_: IOException) {
        }
        escritor.shutdown()
        principal.post(alCerrar)
    }

    companion object {
        private const val TAG = "RodadaEnlace"
    }
}
