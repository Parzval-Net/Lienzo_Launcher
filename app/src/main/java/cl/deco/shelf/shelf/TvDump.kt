package cl.deco.shelf.shelf

import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.util.Log

/**
 * Diagnóstico de TvProvider. Solo se invoca con BuildConfig.TV_DUMP (flag de Gradle -PlienzoTvDump); el build normal
 * no lo trae. Escribe al logcat con el tag ShelfTvDump.
 */
object TvDump {
    private const val TAG = "ShelfTvDump"
    @Volatile private var done = false

    fun run(context: Context) {
        if (done) return
        done = true
        val r = context.applicationContext.contentResolver
        try { watchNext(r) } catch (e: Throwable) { Log.w(TAG, "watch_next: $e") }
        try { channels(r) } catch (e: Throwable) { Log.w(TAG, "channels: $e") }
        Log.i(TAG, "fin")
    }

    private fun watchNext(r: android.content.ContentResolver) {
        r.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
            Log.i(TAG, "WN filas=${c.count}")
            var n = 0
            while (c.moveToNext()) {
                Log.i(
                    TAG,
                    "WN#${n++} pkg=${c.s("package_name")} title='${c.s("title")}' type=${c.s("watch_next_type")} " +
                        "engage=${c.s("last_engagement_time_utc_millis")} pos=${c.s("last_playback_position_millis")} " +
                        "dur=${c.s("duration_millis")} browsable=${c.s("browsable")} " +
                        "intent=${c.s("intent_uri")?.take(90)} poster=${if (c.s("poster_art_uri").isNullOrBlank()) "no" else "si"} " +
                        "thumb=${if (c.s("thumbnail_uri").isNullOrBlank()) "no" else "si"}",
                )
            }
        }
    }

    private fun channels(r: android.content.ContentResolver) {
        r.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            Log.i(TAG, "CH filas=${c.count}")
            while (c.moveToNext()) {
                val id = c.s("_id")?.toLongOrNull() ?: continue
                var count = 0
                var conPos = 0
                var conEng = 0
                val tipos = java.util.TreeSet<String>()
                val titles = ArrayList<String>()
                r.query(
                    TvContract.buildPreviewProgramsUriForChannel(id),
                    null, null, null,
                    "${TvContract.PreviewPrograms.COLUMN_WEIGHT} DESC, ${TvContract.PreviewPrograms._ID} DESC",
                )?.use { p ->
                    count = p.count
                    while (p.moveToNext()) {
                        if (titles.size < 3) titles += p.s("title").orEmpty()
                        if ((p.s("last_playback_position_millis")?.toLongOrNull() ?: 0L) > 0L) conPos++
                        if ((p.s("interaction_count")?.toLongOrNull() ?: 0L) > 0L) conEng++
                        tipos += "t" + p.s("type") + "/i" + p.s("interaction_type")
                    }
                }
                Log.i(
                    TAG,
                    "CH id=$id pkg=${c.s("package_name")} type=${c.s("type")} name='${c.s("display_name")}' " +
                        "browsable=${c.s("browsable")} programas=$count conPos=$conPos conInteraccion=$conEng tipos=$tipos primeros=$titles",
                )
            }
        }
    }

    private fun Cursor.s(col: String): String? {
        val i = getColumnIndex(col)
        return if (i < 0 || isNull(i)) null else try { getString(i) } catch (_: Exception) { "<blob>" }
    }
}
