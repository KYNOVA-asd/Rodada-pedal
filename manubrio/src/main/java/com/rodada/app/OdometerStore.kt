package com.rodada.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Kilometraje acumulado en el S40. Solo suma puntos que RideService ya aceptó. */
internal class OdometerStore(context: Context) : SQLiteOpenHelper(context, "rodada_kilometraje.db", null, 4) {
    /** Resumen de un recorrido terminado. minKmh = -1 si no se movió. */
    data class Ride(
        val seconds: Long, val meters: Double, val maxKmh: Float, val minKmh: Float, val stoppedSeconds: Long,
        val stops: Int = 0, val endedAtMs: Long = 0L
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
        crearEncharcamientos(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) crearRecorridos(db)
        else if (oldVersion < 3) db.execSQL("ALTER TABLE recorridos ADD COLUMN paradas INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4) crearEncharcamientos(db)
    }

    private fun crearEncharcamientos(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE encharcamientos (id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL NOT NULL, lon REAL NOT NULL, creado_ms INTEGER NOT NULL)")
    }

    /** Puntos donde el usuario sabe que se encharca; solo viven en este S40. */
    fun addFloodSpot(lat: Double, lon: Double) {
        writableDatabase.execSQL(
            "INSERT INTO encharcamientos (lat, lon, creado_ms) VALUES (?, ?, ?)",
            arrayOf(lat, lon, System.currentTimeMillis())
        )
    }

    fun floodSpots(): List<Pair<Double, Double>> = readableDatabase.rawQuery(
        "SELECT lat, lon FROM encharcamientos", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getDouble(1)) } }

    fun clearFloodSpots() {
        writableDatabase.execSQL("DELETE FROM encharcamientos")
    }

    private fun crearRecorridos(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE recorridos (id INTEGER PRIMARY KEY AUTOINCREMENT, fin_ms INTEGER NOT NULL, " +
                "segundos INTEGER NOT NULL, metros REAL NOT NULL, max_kmh REAL NOT NULL, " +
                "min_kmh REAL NOT NULL, detenido_s INTEGER NOT NULL, paradas INTEGER NOT NULL DEFAULT 0)"
        )
    }

    fun saveRide(seconds: Long, meters: Double, maxKmh: Float, minKmh: Float, stoppedSeconds: Long, stops: Int) {
        writableDatabase.execSQL(
            "INSERT INTO recorridos (fin_ms, segundos, metros, max_kmh, min_kmh, detenido_s, paradas) VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(System.currentTimeMillis(), seconds, meters, maxKmh, minKmh, stoppedSeconds, stops)
        )
    }

    fun lastRide(): Ride? = rides(1).firstOrNull()

    /** Los últimos [limit] recorridos, del más nuevo al más viejo. */
    fun rides(limit: Int): List<Ride> = readableDatabase.rawQuery(
        "SELECT segundos, metros, max_kmh, min_kmh, detenido_s, paradas, fin_ms FROM recorridos ORDER BY id DESC LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Ride(c.getLong(0), c.getDouble(1), c.getFloat(2), c.getFloat(3), c.getLong(4), c.getInt(5), c.getLong(6)))
            }
        }
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
