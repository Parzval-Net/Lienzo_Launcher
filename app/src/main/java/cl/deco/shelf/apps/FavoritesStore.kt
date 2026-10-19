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

    /** Favoritas por defecto: servicios de streaming conocidos si están instalados, por label. */
    fun defaults(all: List<AppEntry>): List<String> {
        val matchers: List<(String) -> Boolean> = listOf(
            { l -> "netflix" in l },
            { l -> l.startsWith("youtube") && "music" !in l && "kids" !in l },
            { l -> "disney" in l },
            { l -> l == "max" || "hbo max" in l },
            { l -> "prime video" in l },
            { l -> "movistar" in l },
        )
        val out = ArrayList<String>()
        for (m in matchers) {
            all.firstOrNull { m(it.label.lowercase()) && it.key !in out }?.let { out.add(it.key) }
        }
        return out
    }

    private fun split(s: String?) = if (s.isNullOrEmpty()) emptyList() else s.split("|")

    private companion object {
        const val FAVS = "favs"
        const val ORDER = "order"
    }
}
