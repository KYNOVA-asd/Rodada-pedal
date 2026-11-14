package com.rodada.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ListView

/** Elegir qué apps reenvían sus notificaciones al S40. Por defecto ninguna. */
class AppsActivity : Activity() {
    private data class App(val paquete: String, val nombre: String) {
        override fun toString() = nombre
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        val ajustes = Ajustes(this)

        findViewById<CheckBox>(R.id.read_title).apply {
            isChecked = ajustes.leerTitulo
            setOnCheckedChangeListener { _, marcado -> ajustes.leerTitulo = marcado }
        }

        val lanzador = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(lanzador, 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.paquete != packageName }
            .distinctBy { it.paquete }
            .sortedBy { it.nombre.lowercase() }

        val lista = findViewById<ListView>(R.id.apps)
        lista.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        lista.adapter = ArrayAdapter(this, R.layout.item_app, apps)
        val elegidas = ajustes.apps
        apps.forEachIndexed { i, app -> lista.setItemChecked(i, app.paquete in elegidas) }
        lista.setOnItemClickListener { _, _, _, _ ->
            val marcadas = lista.checkedItemPositions
            ajustes.apps = apps.indices.filter { marcadas[it] }.map { apps[it].paquete }.toSet()
        }
    }
}
