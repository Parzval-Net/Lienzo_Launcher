package cl.deco.shelf.apps

import android.content.Context

/** Favoritas y orden de apps en SharedPreferences (claves = ComponentName corto separado por "|"). */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    /** Null en el primer arranque (aún no inicializadas). */
    fun favorites(): List<String>? =
        if (prefs.contains(FAVS)) split(prefs.getString(FAVS, "")) else null

    fun order(): List<String> = split(prefs.getString(ORDER, ""))

    fun save(favs: List<String>?, order: List<String>?) {
        prefs.edit().apply {
            if (favs != null) putString(FAVS, favs.joinToString("|"))
            if (order != null) putString(ORDER, order.joinToString("|"))
        }.apply()
    }

    fun hidden(): Set<String> = prefs.getStringSet(HIDDEN, emptySet()).orEmpty().toSet()

    fun setHidden(h: Set<String>) { prefs.edit().putStringSet(HIDDEN, h).apply() }

    /** Nombres personalizados por paquete (JSON en prefs). */
    fun names(): Map<String, String> = try {
        val o = org.json.JSONObject(prefs.getString(NAMES, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) { emptyMap() }

    fun setName(pkg: String, name: String?) {
        val o = org.json.JSONObject()
        for ((k, v) in names()) if (k != pkg) o.put(k, v)
        if (!name.isNullOrBlank()) o.put(pkg, name)
        prefs.edit().putString(NAMES, o.toString()).apply()
    }

    /** Restablece orden y favoritas al primer arranque (no toca ocultas ni nombres). */
    fun resetLayout() { prefs.edit().remove(FAVS).remove(ORDER).apply() }

    /** Dock por defecto (primer arranque): paquetes en este orden, solo los instalados. */
    fun defaultFavorites(all: List<AppEntry>): List<String> = DEFAULT_FAVS.mapNotNull { pkg ->
        all.firstOrNull { it.component.packageName == pkg }?.key
    }

    /** Orden por defecto de la grilla: entretenimiento, utilidades, ajustes al final. */
    fun defaultOrder(all: List<AppEntry>): List<String> {
        val util = UTILS.mapNotNull { pkg -> all.firstOrNull { it.component.packageName == pkg } }
        val settings = all.filter { it.component.packageName == SETTINGS }
        val rest = all.filter { e -> e !in util && e !in settings }.sortedBy { it.label.lowercase() }
        return (rest + util + settings).map { it.key }
    }

    private fun split(s: String?) = if (s.isNullOrEmpty()) emptyList() else s.split("|")

    companion object {
        const val FAVS = "favs"
        const val ORDER = "order"
        const val HIDDEN = "hidden"
        const val NAMES = "app_names"
        const val SETTINGS = "com.android.tv.settings"
        val DEFAULT_FAVS = listOf(
            "cl.movistarplay", "com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus",
            "com.wbd.stream", "com.amazon.amazonvideo.livingroom", "com.arkiv.player.light",
        )
        val UTILS = listOf("com.android.vending", "com.esaba.downloader", "com.instantbits.cast.receiver")
        /** Nunca se muestran (ni se pueden restaurar). */
        val ALWAYS_HIDDEN = setOf(
            "cl.deco.shelf", "com.spocky.projengmenu", "dev.projectivy.blueprint.app", "com.overdevs.at4k",
        )
    }
}
