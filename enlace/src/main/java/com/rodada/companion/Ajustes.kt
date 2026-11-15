package com.rodada.companion

import android.content.Context

/** Ajustes de Rodada Enlace. Solo guarda qué apps reenviar y opciones, nunca contenido. */
class Ajustes(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("enlace", Context.MODE_PRIVATE)

    /** Paquetes cuyas notificaciones se reenvían al S40. Por defecto, ninguno. */
    var apps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_APPS, value).apply()

    /** Decir en voz alta el título de cada notificación reenviada. */
    var leerTitulo: Boolean
        get() = prefs.getBoolean(KEY_LEER, false)
        set(value) = prefs.edit().putBoolean(KEY_LEER, value).apply()

    /** El usuario dejó el enlace activado; se vuelve a encender al abrir la app. */
    var enlaceActivo: Boolean
        get() = prefs.getBoolean(KEY_ACTIVO, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVO, value).apply()

    /** Contacto de emergencia para caídas (lo elige el usuario en la pantalla). */
    var contactoNombre: String?
        get() = prefs.getString(KEY_CONTACTO_NOMBRE, null)
        set(value) = prefs.edit().putString(KEY_CONTACTO_NOMBRE, value).apply()

    var contactoNumero: String?
        get() = prefs.getString(KEY_CONTACTO_NUMERO, null)
        set(value) = prefs.edit().putString(KEY_CONTACTO_NUMERO, value).apply()

    companion object {
        private const val KEY_CONTACTO_NOMBRE = "contacto_nombre"
        private const val KEY_CONTACTO_NUMERO = "contacto_numero"
        private const val KEY_ACTIVO = "enlace_activo"
        private const val KEY_APPS = "apps_reenviadas"
        private const val KEY_LEER = "leer_titulo"
    }
}
