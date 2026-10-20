package cl.deco.shelf.data.art

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Mis fondos": imágenes importadas por el usuario en `getExternalFilesDir("art_user")`, con un index.json propio
 * del mismo formato que la colección curada (más la clave "key" para no importar dos veces el mismo archivo).
 * Bloqueante: llamar desde executor.
 */
class UserArt(context: Context) : ArtSource {

    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences("user_art", Context.MODE_PRIVATE) }
    private val dir: File? get() = app.getExternalFilesDir(DIR)

    data class Entry(val id: String, val title: String, val file: File, val bytes: Long, val home: Boolean, val key: String)

    private fun indexFile(d: File) = File(d, "index.json")

    private fun readArray(d: File): JSONArray = try {
        val f = indexFile(d)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        Log.w(TAG, "index.json inválido: ${e.message}")
        JSONArray()
    }

    /** Obras con su JPEG en disco, en orden de importación. */
    fun entries(): List<Entry> = synchronized(LOCK) {
        val d = dir ?: return emptyList()
        val arr = readArray(d)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = File(d, o.optString("file"))
            if (!f.exists() || f.length() == 0L) return@mapNotNull null
            Entry(o.getString("id"), o.optString("title"), f, f.length(), o.optBoolean("home", true), o.optString("key"))
        }
    }

    fun count(forHome: Boolean = false) = entries().count { !forHome || it.home }

    fun importedKeys(): Set<String> = entries().map { it.key }.filter { it.isNotEmpty() }.toSet()

    override fun next(forHome: Boolean): Artwork? = synchronized(LOCK) {
        val list = entries().filter { !forHome || it.home }
        if (list.isEmpty()) return null
        val key = if (forHome) KEY_HOME else KEY_ALL
        val idx = (prefs.getInt(key, -1) + 1).mod(list.size)
        prefs.edit().putInt(key, idx).apply()
        val e = list[idx]
        Artwork("user-${e.id}", e.title, "Mis fondos", null, "", e.file)
    }

    /** Nada que descargar. */
    override fun prefetch(max: Int) {}

    /** Carpeta de destino (se crea si falta). */
    fun targetDir(): File? = dir?.also { it.mkdirs() }

    /** Id nuevo y libre en disco. */
    fun newId(): String = synchronized(LOCK) {
        val d = dir
        var n = System.currentTimeMillis()
        while (d != null && File(d, "u$n.jpg").exists()) n++
        "u$n"
    }

    /** Registra una obra ya guardada en [Entry.file] (mismo formato del index de la colección curada). */
    fun add(e: Entry, mode: String) = synchronized(LOCK) {
        val d = dir ?: return
        val arr = readArray(d)
        val o = JSONObject()
            .put("id", e.id)
            .put("title", e.title)
            .put("year", "")
            .put("museum", "")
            .put("source_url", "")
            .put("license", "Archivo propio")
            .put("artist", "Mis fondos")
            .put("file", e.file.name)
            .put("mode", mode)
            .put("bytes", e.bytes)
            .put("key", e.key)
        if (!e.home) o.put("home", false)
        arr.put(o)
        write(d, arr)
    }

    /** Borra la obra y su archivo. */
    fun remove(id: String) = synchronized(LOCK) {
        val d = dir ?: return
        val arr = readArray(d)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) File(d, o.optString("file")).delete() else out.put(o)
        }
        write(d, out)
    }

    private fun write(d: File, arr: JSONArray) {
        val tmp = File(d, "index.json.tmp")
        tmp.writeText(arr.toString(1))
        if (!tmp.renameTo(indexFile(d))) Log.w(TAG, "no se pudo reemplazar index.json")
    }

    companion object {
        const val DIR = "art_user"
        private const val TAG = "UserArt"
        private const val KEY_HOME = "last_home"
        private const val KEY_ALL = "last_all"
        private val LOCK = Any()
    }
}
