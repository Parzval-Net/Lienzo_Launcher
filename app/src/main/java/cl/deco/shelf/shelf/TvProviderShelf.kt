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

    /** Candidatos de "Continuar viendo": Watch Next y canales "Seguir viendo" (los llena preload; sin queries extra). */
    @Volatile private var wnCands: List<ContinueSelector.Candidate> = emptyList()
    @Volatile private var chCands: List<ContinueSelector.Candidate> = emptyList()

    /** Item de "Continuar viendo": paquete de origen y contenido. */
    class Continue(val pkg: String, val item: TopShelfItem)

    /**
     * Continuar viendo: Watch Next más canales preview "Seguir viendo", con la selección de [ContinueSelector]
     * (orden por engagement, deduplicado por paquete + título). Usa la lectura de [preload]; no consulta el provider.
     */
    fun continueWatching(max: Int = 12): List<Continue> =
        ContinueSelector.select(wnCands + chCands, max).map { Continue(it.pkg, it.item) }

    private class Entry(val at: Long, val items: List<TopShelfItem>)

    override fun itemsFor(packageName: String): List<TopShelfItem> {
        synchronized(cache) {
            cache[packageName]?.let { if (now() - it.at < TTL_MS) return it.items }
        }
        val items = try {
            val watch = readWatchNext().byPkg[packageName].orEmpty()
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
        val wn = try {
            readWatchNext()
        } catch (e: SecurityException) {
            logDenied(e)
            pkgs.forEach { put(it, emptyList()) }
            return
        } catch (e: Exception) {
            Log.w(TAG, "preload: $e")
            return
        }
        wnCands = wn.candidates
        // Preview: una query de canales y una por canal con programas (el provider no admite selection en esas URIs)
        val previews = try { readPreviewAll(pkgs.toSet(), wn.candidates.size) } catch (e: SecurityException) { logDenied(e); null } catch (e: Exception) {
            Log.w(TAG, "preload preview: $e"); null
        }
        chCands = previews?.continueItems.orEmpty()
        Log.i(TAG, "TvProvider: watch_next=${wn.candidates.size}, canales 'seguir viendo'=${chCands.size}, apps con preview=${previews?.byPkg?.count { it.value.isNotEmpty() } ?: 0}")
        for (pkg in pkgs) put(pkg, merge(wn.byPkg[pkg].orEmpty(), previews?.byPkg?.get(pkg).orEmpty()))
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

    private class WatchNext(val byPkg: Map<String, List<TopShelfItem>>, val candidates: List<ContinueSelector.Candidate>)

    /** Todo Watch Next agrupado por paquete (filtra en memoria, más robusto entre builds). */
    private fun readWatchNext(): WatchNext {
        val out = LinkedHashMap<String, MutableList<TopShelfItem>>()
        val cands = ArrayList<ContinueSelector.Candidate>()
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
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        resolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
            "${TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val pkg = c.str(0) ?: continue
                var item = c.toItem(1, 2, 3, 4, 5, 6) ?: continue
                item = withProgress(item, c, 7, 8)
                out.getOrPut(pkg) { ArrayList() }.add(item)
                val type = if (c.isNull(9)) null else c.getInt(9)
                val engagement = if (c.isNull(10)) null else c.getLong(10)
                cands += ContinueSelector.Candidate(pkg, item, engagement, type, cands.size)
            }
        }
        return WatchNext(out, cands)
    }

    private fun withProgress(item: TopShelfItem, c: Cursor, posCol: Int, durCol: Int): TopShelfItem {
        val pos = if (c.isNull(posCol)) 0L else c.getLong(posCol)
        val dur = if (c.isNull(durCol)) 0L else c.getLong(durCol)
        return if (pos > 0 && dur > 0) item.copy(progress = (pos.toFloat() / dur).coerceIn(0f, 1f)) else item
    }

    private fun readPreview(pkg: String): List<TopShelfItem> = readPreviewAll(setOf(pkg), 0).byPkg[pkg].orEmpty()

    private class Previews(val byPkg: Map<String, List<TopShelfItem>>, val continueItems: List<ContinueSelector.Candidate>)

    /**
     * PreviewPrograms de los canales preview de [pkgs]. El TvProvider lanza SecurityException si Channels o PreviewPrograms
     * llevan selection (verificado en el deco), así que se lee Channels completo, se filtra por tipo en memoria y se pide cada
     * canal con su URI propia (buildPreviewProgramsUriForChannel). El canal browsable va primero. Los canales cuyo nombre es
     * "Seguir viendo" / "Continue watching" aportan además candidatos a "Continuar viendo" (con o sin browsable).
     */
    private fun readPreviewAll(pkgs: Set<String>, seqBase: Int): Previews {
        class Ch(val id: Long, val pkg: String, val browsable: Boolean, val cw: Boolean)
        val channels = ArrayList<Ch>()
        resolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(
                TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_BROWSABLE,
                TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val pkg = c.str(1) ?: continue
                if (pkg !in pkgs || c.str(3) != TvContract.Channels.TYPE_PREVIEW) continue
                channels += Ch(c.getLong(0), pkg, !c.isNull(2) && c.getInt(2) == 1, ContinueSelector.isContinueChannel(c.str(4)))
            }
        }
        if (channels.isEmpty()) return Previews(emptyMap(), emptyList())
        val pCols = arrayOf(
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
        )
        val out = HashMap<String, MutableList<TopShelfItem>>()
        val cont = ArrayList<ContinueSelector.Candidate>()
        // Por paquete: canales browsable primero; un canal "Seguir viendo" se lee siempre, el resto solo mientras falte para MAX_ITEMS
        for (ch in channels.sortedWith(compareByDescending<Ch> { it.browsable }.thenBy { it.id })) {
            val dest = out.getOrPut(ch.pkg) { ArrayList() }
            if (!ch.cw && dest.size >= MAX_ITEMS) continue
            val list = ArrayList<TopShelfItem>()
            resolver.query(
                TvContract.buildPreviewProgramsUriForChannel(ch.id), pCols, null, null,
                "${TvContract.PreviewPrograms.COLUMN_WEIGHT} DESC, ${TvContract.PreviewPrograms._ID} DESC",
            )?.use { c ->
                while (c.moveToNext() && list.size < MAX_ITEMS) {
                    c.toItem(0, 1, 2, 3, 4, 5)?.let { list += withProgress(it, c, 6, 7) }
                }
            }
            for (it in list) if (dest.size < MAX_ITEMS) dest += it
            if (ch.cw) for (it in list) cont += ContinueSelector.Candidate(ch.pkg, it, null, null, seqBase + cont.size)
        }
        return Previews(out, cont)
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
