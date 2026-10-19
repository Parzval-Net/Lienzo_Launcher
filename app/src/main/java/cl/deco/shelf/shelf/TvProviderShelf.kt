package cl.deco.shelf.shelf

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log

/**
 * Lee Watch Next y preview channels del TvProvider (sin androidx.tvprovider).
 * Bloqueante: llamar fuera del main thread.
 */
class TvProviderShelf(context: Context) : TopShelfProvider {
    private val resolver = context.applicationContext.contentResolver
    private val cache = HashMap<String, Entry>()
    private var loggedDenied = false

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
        for (pkg in pkgs) {
            val items = try {
                merge(grouped[pkg].orEmpty(), readPreview(pkg))
            } catch (e: SecurityException) {
                logDenied(e); emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "preload($pkg): $e"); emptyList()
            }
            put(pkg, items)
        }
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
        val cols = arrayOf(
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
        )
        resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val pkg = c.str(0) ?: continue
                val item = c.toItem(1, 2, 3, 4, 5, 6) ?: continue
                out.getOrPut(pkg) { ArrayList() }.add(item)
            }
        }
        return out
    }

    private fun readPreview(pkg: String): List<TopShelfItem> {
        // Canales preview del paquete; el browsable va primero.
        class Ch(val id: Long, val browsable: Boolean)
        val channels = ArrayList<Ch>()
        val cCols = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_BROWSABLE,
        )
        resolver.query(TvContract.Channels.CONTENT_URI, cCols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.str(1) != pkg || c.str(2) != TvContract.Channels.TYPE_PREVIEW) continue
                channels += Ch(c.getLong(0), !c.isNull(3) && c.getInt(3) == 1)
            }
        }
        if (channels.isEmpty()) return emptyList()
        val ordered = channels.sortedByDescending { it.browsable }
        val pCols = arrayOf(
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
        )
        val out = ArrayList<TopShelfItem>()
        for (ch in ordered) {
            val uri: Uri = TvContract.buildPreviewProgramsUriForChannel(ch.id)
            resolver.query(uri, pCols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_ITEMS) {
                    c.toItem(0, 1, 2, 3, 4, 5)?.let { out += it }
                }
            }
            if (out.size >= MAX_ITEMS) break
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
