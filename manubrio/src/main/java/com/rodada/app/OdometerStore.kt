package com.rodada.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Kilometraje acumulado en el S40. Solo suma puntos que RideService ya aceptó. */
internal class OdometerStore(context: Context) : SQLiteOpenHelper(context, "rodada_kilometraje.db", null, 2) {
    /** Resumen de un recorrido terminado. minKmh = -1 si no se movió. */
    data class Ride(
        val seconds: Long, val meters: Double, val maxKmh: Float, val minKmh: Float, val stoppedSeconds: Long
    ) {
        /** Promedio solo del tiempo en movimiento. */
        val averageKmh: Float
            get() {
                val moving = seconds - stoppedSeconds
                return if (moving > 0) (meters / moving * 3.6).toFloat() else 0f
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE odometro (id INTEGER PRIMARY KEY, metros REAL NOT NULL)")
        db.execSQL("INSERT INTO odometro (id, metros) VALUES (1, 0)")
        crearRecorridos(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) crearRecorridos(db)
    }

    private fun crearRecorridos(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE recorridos (id INTEGER PRIMARY KEY AUTOINCREMENT, fin_ms INTEGER NOT NULL, " +
                "segundos INTEGER NOT NULL, metros REAL NOT NULL, max_kmh REAL NOT NULL, " +
                "min_kmh REAL NOT NULL, detenido_s INTEGER NOT NULL)"
        )
    }

    fun saveRide(seconds: Long, meters: Double, maxKmh: Float, minKmh: Float, stoppedSeconds: Long) {
        writableDatabase.execSQL(
            "INSERT INTO recorridos (fin_ms, segundos, metros, max_kmh, min_kmh, detenido_s) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(System.currentTimeMillis(), seconds, meters, maxKmh, minKmh, stoppedSeconds)
        )
    }

    fun lastRide(): Ride? = readableDatabase.rawQuery(
        "SELECT segundos, metros, max_kmh, min_kmh, detenido_s FROM recorridos ORDER BY id DESC LIMIT 1", null
    ).use { c ->
        if (c.moveToFirst()) Ride(c.getLong(0), c.getDouble(1), c.getFloat(2), c.getFloat(3), c.getLong(4)) else null
    }

    /** Velocidad máxima de todos los recorridos guardados. */
    fun recordMaxKmh(): Float = readableDatabase.rawQuery(
        "SELECT MAX(max_kmh) FROM recorridos", null
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getFloat(0) else 0f }

    fun addMeters(meters: Double) {
        if (meters <= 0.0) return
        writableDatabase.execSQL("UPDATE odometro SET metros = metros + ? WHERE id = 1", arrayOf(meters))
    }

    fun totalMeters(): Double = readableDatabase.rawQuery(
        "SELECT metros FROM odometro WHERE id = 1", null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0 }

    fun reset() {
        writableDatabase.execSQL("UPDATE odometro SET metros = 0 WHERE id = 1")
    }
}
