package cl.deco.shelf.data.art

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Mis fondos": imágenes importadas por el usuario en `getExternalFilesDir("art_user")`, con un index.json propio
 * del mismo formato que la colección curada (más "key" para no importar dos veces el mismo archivo y "collection",
 * la colección temática; sin ella es General). El fondo y el protector usan solo la colección activa.
 * Bloqueante: llamar desde executor.
 */
class UserArt(context: Context) : ArtSource {

    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences("user_art", Context.MODE_PRIVATE) }
    private val dir: File? get() = app.getExternalFilesDir(DIR)

    data class Entry(val id: String, val title: String, val file: File, val bytes: Long, val home: Boolean, val key: String,
                     val collection: String = Collections.GENERAL)

    /** Colección activa (Ajustes > Pantalla y fondos > Colección): "" = todas. */
    private val active: String get() = cl.deco.shelf.settings.ShelfPrefs(app).collection

    private fun indexFile(d: File) = File(d, "index.json")

    private fun readArray(d: File): JSONArray = try {
        val f = android.util.AtomicFile(indexFile(d))
        if (f.baseFile.exists()) JSONArray(String(f.readFully(), Charsets.UTF_8)) else JSONArray()
    } catch (e: Exception) {
        // Índice dañado (corte de luz a mitad de escritura): se reconstruye desde los JPEG en disco en vez de tratarlo
        // como vacío, porque la siguiente escritura lo habría guardado así y las importaciones previas quedaban huérfanas
        Log.w(TAG, "index.json inválido, se reconstruye: ${e.message}")
        rebuildFromDisk(d)
    }

    private fun rebuildFromDisk(d: File): JSONArray {
        val arr = JSONArray()
        d.listFiles { f -> f.name.startsWith("u") && f.name.endsWith(".jpg") && f.length() > 0 }?.sortedBy { it.name }?.forEach { f ->
            arr.put(JSONObject().put("id", f.nameWithoutExtension).put("title", f.nameWithoutExtension).put("file", f.name)
                .put("license", "Archivo propio").put("artist", "Mis fondos").put("bytes", f.length()).put("key", ""))
        }
        return arr
    }

    /** Obras con su JPEG en disco, en orden de importación. */
    fun entries(): List<Entry> = synchronized(LOCK) {
        val d = dir ?: return emptyList()
        val arr = readArray(d)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = File(d, o.optString("file"))
            if (!f.exists() || f.length() == 0L) return@mapNotNull null
            Entry(o.getString("id"), o.optString("title"), f, f.length(), o.optBoolean("home", true), o.optString("key"),
                o.optString("collection").ifBlank { Collections.GENERAL })
        }
    }

    /** Obras de la colección activa (las que usan el fondo y el protector). */
    private fun activeEntries(): List<Entry> { val a = active; return entries().filter { Collections.matches(a, it.collection) } }

    fun count(forHome: Boolean = false) = activeEntries().count { !forHome || it.home }

    /** Colecciones con al menos una imagen, General primero. */
    fun collections(): List<String> = Collections.sorted(entries().map { it.collection })

    /** Borra todas las imágenes de una colección. */
    fun removeCollection(name: String) = synchronized(LOCK) {
        val d = dir ?: return
        val arr = readArray(d)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("collection").ifBlank { Collections.GENERAL } == name) File(d, o.optString("file")).delete() else out.put(o)
        }
        write(d, out)
    }

    fun importedKeys(): Set<String> = entries().map { it.key }.filter { it.isNotEmpty() }.toSet()

    override fun next(forHome: Boolean): Artwork? = synchronized(LOCK) {
        val list = activeEntries().filter { !forHome || it.home }
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
            .put("collection", e.collection)
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

    /** Escritura atómica con fsync (AtomicFile): un corte de luz deja el índice anterior o el nuevo, nunca uno truncado. */
    private fun write(d: File, arr: JSONArray) {
        val f = android.util.AtomicFile(indexFile(d))
        val out = try { f.startWrite() } catch (e: Exception) { Log.w(TAG, "no se pudo escribir index.json: $e"); return }
        try {
            out.write(arr.toString(1).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            Log.w(TAG, "no se pudo escribir index.json: $e")
        }
    }

    companion object {
        const val DIR = "art_user"
        private const val TAG = "UserArt"
        private const val KEY_HOME = "last_home"
        private const val KEY_ALL = "last_all"
        private val LOCK = Any()
    }
}
