package cl.deco.shelf.shelf

import java.text.Normalizer

/**
 * Selección y orden de "Continuar viendo" (lógica pura, sin Android, con tests JVM).
 *
 * Fuentes: Watch Next de todas las apps y programas de canales preview cuyo nombre es "Seguir viendo" / "Continue watching".
 * Orden: primero lo que tiene last_engagement (grupo CONTINUE/NEXT antes que NEW/WATCHLIST, luego más reciente primero) y
 * después lo que no lo tiene, sin descartarlo. Deduplica por paquete + título normalizado. Tope [max] con un máximo blando
 * por app para que una sola app no tape a las demás; si sobra espacio se completa con lo que quedó fuera.
 */
object ContinueSelector {
    const val TYPE_CONTINUE = 0
    const val TYPE_NEXT = 1
    const val TYPE_NEW = 2
    const val TYPE_WATCHLIST = 3

    class Candidate(
        val pkg: String,
        val item: TopShelfItem,
        /** last_engagement_time_utc_millis, o null si el app no lo publica (también si es 0 o negativo). */
        engagement: Long?,
        /** watch_next_type; null para programas de canal preview. */
        val type: Int?,
        /** Posición en la lectura original (desempate estable). */
        val seq: Int,
    ) {
        val engagement: Long? = engagement?.takeIf { it > 0L }

        /** CONTINUE y NEXT (y programas de canal "seguir viendo") antes que NEW y WATCHLIST. */
        val group: Int get() = if (type == TYPE_NEW || type == TYPE_WATCHLIST) 1 else 0
    }

    private val ORDER: Comparator<Candidate> =
        compareBy<Candidate> { if (it.engagement != null) 0 else 1 }
            .thenBy { it.group }
            .thenByDescending { it.engagement ?: 0L }
            .thenBy { it.seq }

    fun select(all: List<Candidate>, max: Int = 12, perPkgSoftCap: Int = 4): List<Candidate> {
        val seen = HashSet<String>()
        val sorted = ArrayList<Candidate>()
        for (c in all.sortedWith(ORDER)) if (seen.add(dedupKey(c.pkg, c.item.title))) sorted += c
        val taken = HashSet<Candidate>()
        val perPkg = HashMap<String, Int>()
        for (c in sorted) {
            if (taken.size >= max) break
            val n = perPkg[c.pkg] ?: 0
            if (n < perPkgSoftCap) { taken += c; perPkg[c.pkg] = n + 1 }
        }
        for (c in sorted) { if (taken.size >= max) break; taken += c }
        return sorted.filter { it in taken }
    }

    fun dedupKey(pkg: String, title: String): String = pkg + "|" + title.lowercase().filter { it.isLetterOrDigit() }

    private val CONTINUE_CHANNEL = Regex(
        "\\b(continu\\w*|segui\\w*|reanud\\w*|retom\\w*|resum\\w*|keep|reprend\\w*|prosegui\\w*)\\s+" +
            "(viendo|mirando|ver|a ver|watching|watch|playing|assistindo|regarder|a regarder|reproduciendo|guardare|a guardare)\\b" +
            "|\\bweiterschauen\\b|\\bcontinue\\s+a\\s+ver\\b",
    )

    /**
     * ¿El nombre del canal preview corresponde a "Seguir viendo"? Sin distinguir mayúsculas, tildes ni idioma (es/en/pt/fr/de/it).
     * SmartTube no publica Watch Next (su addWatchNextContinue nunca se llama): su canal "Historial" hace de "Seguir viendo",
     * solo para sus paquetes; en otras apps un historial puede ser cualquier cosa (compras, búsquedas).
     */
    fun isContinueChannel(displayName: String?, pkg: String? = null): Boolean {
        val n = displayName?.let { normalize(it) } ?: return false
        if (CONTINUE_CHANNEL.containsMatchIn(n)) return true
        return pkg in HISTORY_AS_CONTINUE && HISTORY_CHANNEL.containsMatchIn(n)
    }

    /** SmartTube estable y beta (y su id anterior). */
    private val HISTORY_AS_CONTINUE = setOf("org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv.beta", "com.teamsmart.videomanager.tv")
    private val HISTORY_CHANNEL = Regex("\\b(historial|history|historico|historique|verlauf|cronologia)\\b")

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()
}
