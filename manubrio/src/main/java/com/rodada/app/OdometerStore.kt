package com.rodada.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Kilometraje acumulado en el S40. Solo suma puntos que RideService ya aceptó. */
internal class OdometerStore(context: Context) : SQLiteOpenHelper(context, "rodada_kilometraje.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE odometro (id INTEGER PRIMARY KEY, metros REAL NOT NULL)")
        db.execSQL("INSERT INTO odometro (id, metros) VALUES (1, 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
