package cl.deco.shelf.shelf

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.util.Log

/**
 * Lee Watch Next y preview channels del TvProvider (sin androidx.tvprovider).
 * Bloqueante: llamar fuera del main thread.
 */
class TvProviderShelf(context: Context) : TopShelfProvider {
    private val resolver = context.applicationContext.contentResolver
    private val cache = HashMap<String, Entry>()
    private var loggedDenied = false

    /** Watch Next de todas las apps en orden de interacción reciente (lo llena preload; sin queries extra). */
    @Volatile private var flat: List<Pair<String, TopShelfItem>> = emptyList()

    /** Item de "Continuar viendo": paquete de origen y contenido. */
    class Continue(val pkg: String, val item: TopShelfItem)

    /**
     * Continuar viendo: Watch Next de todas las apps, orden por last_engagement DESC, deduplicado por paquete más
     * título normalizado. Usa la lectura de [preload]; no consulta el provider.
     */
    fun continueWatching(max: Int = 12): List<Continue> {
        val seen = HashSet<String>()
        val out = ArrayList<Continue>()
        for ((pkg, it) in flat) {
            val key = pkg + "|" + it.title.lowercase().filter { c -> c.isLetterOrDigit() }
            if (seen.add(key)) out += Continue(pkg, it)
            if (out.size >= max) break
        }
        return out
    }

    private class Entry(val at: Long, val items: List<TopShelfItem>)

    override fun itemsFor(packageName: String): List<TopShelfItem> {
        synchronized(cache) {
            cache[packageName]?.let { if (now() - it.at < TTL_MS) return it.items }
        }
        val items = try {
            val watch = readWatchNext()[packageName].orEmpty()
            merge(watch, readPreview(packageName))
        } catch (e: SecurityException) {
            logDenied(e)
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "itemsFor($packageName): $e")
            emptyList()
        }
        put(packageName, items)
        return items
    }

    /** Una sola pasada sobre Watch Next para todos los paquetes; el resto (preview) se lee bajo demanda. */
    fun preload(pkgs: List<String>) {
        val grouped = try {
            readWatchNext()
        } catch (e: SecurityException) {
            logDenied(e)
            pkgs.forEach { put(it, emptyList()) }
            return
        } catch (e: Exception) {
            Log.w(TAG, "preload: $e")
            return
        }
        // Preview: UNA query de canales y UNA de programas para todos los paquetes (antes: 1 + N canales por app)
        val previews = try { readPreviewAll(pkgs.toSet()) } catch (e: SecurityException) { logDenied(e); emptyMap() } catch (e: Exception) {
            Log.w(TAG, "preload preview: $e"); emptyMap()
        }
        for (pkg in pkgs) put(pkg, merge(grouped[pkg].orEmpty(), previews[pkg].orEmpty()))
    }

    private fun merge(watch: List<TopShelfItem>, preview: List<TopShelfItem>): List<TopShelfItem> =
        (watch + preview).distinctBy { it.intentUri ?: it.title }.take(MAX_ITEMS)

    private fun put(pkg: String, items: List<TopShelfItem>) {
        synchronized(cache) { cache[pkg] = Entry(now(), items) }
    }

    private fun now() = System.currentTimeMillis()

    private fun logDenied(e: SecurityException) {
        if (!loggedDenied) {
            loggedDenied = true
            Log.w(TAG, "Sin permiso para leer TvProvider (READ_TV_LISTINGS / READ_EPG_DATA): ${e.message}")
        }
    }

    /** Todo Watch Next agrupado por paquete (filtra en memoria, más robusto entre builds). */
    private fun readWatchNext(): Map<String, List<TopShelfItem>> {
        val out = LinkedHashMap<String, MutableList<TopShelfItem>>()
        val order = ArrayList<Pair<String, TopShelfItem>>() // orden global por interacción reciente
        val cols = arrayOf(
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        )
        resolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
            "${TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val pkg = c.str(0) ?: continue
                var item = c.toItem(1, 2, 3, 4, 5, 6) ?: continue
                val pos = if (c.isNull(7)) 0L else c.getLong(7)
                val dur = if (c.isNull(8)) 0L else c.getLong(8)
                if (pos > 0 && dur > 0) item = item.copy(progress = (pos.toFloat() / dur).coerceIn(0f, 1f))
                out.getOrPut(pkg) { ArrayList() }.add(item)
                order += pkg to item
            }
        }
        flat = order
        return out
    }

    private fun readPreview(pkg: String): List<TopShelfItem> = readPreviewAll(setOf(pkg))[pkg].orEmpty()

    /**
     * PreviewPrograms de los canales preview de [pkgs]: una query a Channels (proyección mínima, filtrada por tipo) y una
     * a PreviewPrograms.CONTENT_URI filtrada por esos canales; se agrupa por channel_id en memoria. El canal browsable va primero.
     */
    private fun readPreviewAll(pkgs: Set<String>): Map<String, List<TopShelfItem>> {
        class Ch(val id: Long, val pkg: String, val browsable: Boolean)
        val channels = HashMap<Long, Ch>()
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_BROWSABLE),
            "${TvContract.Channels.COLUMN_TYPE}=?", arrayOf(TvContract.Channels.TYPE_PREVIEW), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val pkg = c.str(1) ?: continue
                if (pkg in pkgs) channels[c.getLong(0)] = Ch(c.getLong(0), pkg, !c.isNull(2) && c.getInt(2) == 1)
            }
        }
        if (channels.isEmpty()) return emptyMap()
        val ids = channels.keys.joinToString(",")
        // channel_id va primero: agrupa; el resto es la proyección mínima para armar TopShelfItem
        val pCols = arrayOf(
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
        )
        val byChannel = HashMap<Long, MutableList<TopShelfItem>>()
        resolver.query(
            TvContract.PreviewPrograms.CONTENT_URI, pCols,
            "${TvContract.PreviewPrograms.COLUMN_CHANNEL_ID} IN ($ids)", null,
            "${TvContract.PreviewPrograms.COLUMN_WEIGHT} DESC, ${TvContract.PreviewPrograms._ID} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val list = byChannel.getOrPut(c.getLong(0)) { ArrayList() }
                if (list.size >= MAX_ITEMS) continue
                c.toItem(1, 2, 3, 4, 5, 6)?.let { list += it }
            }
        }
        val out = HashMap<String, MutableList<TopShelfItem>>()
        // Por paquete: canales browsable primero y, dentro, el orden de peso ya aplicado
        for (ch in channels.values.sortedWith(compareByDescending<Ch> { it.browsable }.thenBy { it.id })) {
            val l = byChannel[ch.id] ?: continue
            val dest = out.getOrPut(ch.pkg) { ArrayList() }
            for (it in l) if (dest.size < MAX_ITEMS) dest += it
        }
        return out
    }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toItem(title: Int, desc: Int, episode: Int, poster: Int, thumb: Int, intent: Int): TopShelfItem? {
        val t = str(title)?.takeIf { it.isNotBlank() } ?: return null
        return TopShelfItem(
            title = t,
            subtitle = (str(episode) ?: str(desc))?.takeIf { it.isNotBlank() },
            imageUri = str(poster) ?: str(thumb),
            intentUri = str(intent),
        )
    }

    private companion object {
        const val TAG = "Shelf"
        const val TTL_MS = 5 * 60 * 1000L
        const val MAX_ITEMS = 12
    }
}
