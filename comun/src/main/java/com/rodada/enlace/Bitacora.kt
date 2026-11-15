package com.rodada.enlace

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * Bitácora para analizar las rodadas: eventos (tipo, un número y un detalle corto)
 * en SQLite, en un hilo aparte. Nunca guarda el contenido de notificaciones,
 * mensajes ni indicaciones de Maps; solo que ocurrieron. Se borra lo de más de
 * [DIAS] días. Base: rodada_bitacora.db, tabla eventos(ms, tipo, valor, detalle).
 */
object Bitacora {
    private const val DIAS = 30
    private val hilo = Executors.newSingleThreadExecutor()
    @Volatile private var base: Base? = null

    private class Base(context: Context) : SQLiteOpenHelper(context, "rodada_bitacora.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE eventos (id INTEGER PRIMARY KEY AUTOINCREMENT, ms INTEGER NOT NULL, " +
                    "tipo TEXT NOT NULL, valor REAL, detalle TEXT)"
            )
            db.execSQL("CREATE INDEX eventos_ms ON eventos(ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun base(context: Context): Base = base ?: synchronized(this) {
        base ?: Base(context.applicationContext).also { nueva ->
            base = nueva
            hilo.execute {
                try {
                    val limite = System.currentTimeMillis() - DIAS * 24L * 60 * 60 * 1000
                    nueva.writableDatabase.delete("eventos", "ms < ?", arrayOf(limite.toString()))
                } catch (_: Exception) {
                }
            }
        }
    }

    /** "bat=80 temp=40.1" para anotar al iniciar y terminar (consumo por recorrido). */
    fun bateria(context: Context): String {
        val estado = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val nivel = estado?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temp = (estado?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return "bat=$nivel temp=$temp"
    }

    fun anotar(context: Context, tipo: String, valor: Double? = null, detalle: String? = null) {
        val db = base(context)
        val ms = System.currentTimeMillis()
        hilo.execute {
            try {
                db.writableDatabase.insert("eventos", null, ContentValues().apply {
                    put("ms", ms)
                    put("tipo", tipo)
                    if (valor != null) put("valor", valor)
                    if (detalle != null) put("detalle", detalle.take(200))
                })
            } catch (_: Exception) {
                // La bitácora nunca debe tumbar la app.
            }
        }
    }
}
