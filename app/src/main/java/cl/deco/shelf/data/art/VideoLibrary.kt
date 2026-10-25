package cl.deco.shelf.data.art

import android.content.Context
import cl.deco.shelf.settings.ShelfPrefs
import java.io.File

/**
 * Videos del modo arte y del protector: el de Monet (`getExternalFilesDir("video")`, cargado por adb) y los propios
 * (`getExternalFilesDir("video_user")`). Los propios van en una subcarpeta por colección (General = la raíz); su nombre
 * es la ruta relativa ("Warhammer 40k/nebulosa.mp4"). La selección vive en [ShelfPrefs.videoChoice] y es común a ambos usos;
 * la rotación y la elección se limitan a la colección activa.
 */
object VideoLibrary {
    const val USER_DIR = "video_user"
    private const val TICK_PREFS = "video_library"

    /** Video propio con su nombre relativo y su colección. */
    class UserVideo(val file: File, val name: String, val collection: String)

    fun monet(ctx: Context): File? =
        ctx.getExternalFilesDir("video")?.listFiles { f -> f.extension.equals("mp4", true) }?.minByOrNull { it.name }

    fun userDir(ctx: Context): File? = ctx.getExternalFilesDir(USER_DIR)

    /** Carpeta de una colección (se crea si falta). */
    fun collectionDir(ctx: Context, collection: String): File? {
        val root = userDir(ctx) ?: return null
        val sub = Collections.dirName(collection)
        return (if (sub.isEmpty()) root else File(root, sub)).also { it.mkdirs() }
    }

    private fun isVideo(f: File) = f.isFile && f.extension.lowercase() in VideoRules.EXTENSIONS && f.length() > 0

    /** Todos los videos propios: los de la raíz (General) y los de cada subcarpeta de colección. */
    fun all(ctx: Context): List<UserVideo> {
        val root = userDir(ctx) ?: return emptyList()
        val out = ArrayList<UserVideo>()
        root.listFiles()?.forEach { f ->
            when {
                isVideo(f) -> out += UserVideo(f, f.name, Collections.GENERAL)
                f.isDirectory -> f.listFiles()?.filter { isVideo(it) }?.forEach { v -> out += UserVideo(v, "${f.name}/${v.name}", f.name) }
            }
        }
        return out.sortedWith(compareBy({ it.collection != Collections.GENERAL }, { it.name.lowercase() }))
    }

    /** Videos de la colección activa ("" = todas). */
    fun userVideos(ctx: Context): List<UserVideo> {
        val active = ShelfPrefs(ctx).collection
        return all(ctx).filter { Collections.matches(active, it.collection) }
    }

    fun collections(ctx: Context): List<String> = Collections.sorted(all(ctx).map { it.collection })

    fun delete(ctx: Context, name: String): Boolean {
        val v = all(ctx).firstOrNull { it.name == name } ?: return false
        val ok = v.file.delete()
        v.file.parentFile?.takeIf { it != userDir(ctx) && it.list().isNullOrEmpty() }?.delete()
        return ok
    }

    /** Borra los videos de una colección. */
    fun removeCollection(ctx: Context, collection: String) {
        all(ctx).filter { it.collection == collection }.forEach { delete(ctx, it.name) }
    }

    /** Hay algún video elegible (no avanza la rotación de "Alternar todos"). */
    fun hasVideo(ctx: Context, prefs: ShelfPrefs): Boolean = resolve(ctx, prefs, 0) != null

    /** Video para esta reproducción; en "Alternar todos" avanza al siguiente cada vez. */
    fun pick(ctx: Context, prefs: ShelfPrefs): File? {
        val sp = ctx.applicationContext.getSharedPreferences(TICK_PREFS, Context.MODE_PRIVATE)
        val tick = if (prefs.videoChoice == VideoPolicy.ALL) sp.getInt("tick", 0) else 0
        val f = resolve(ctx, prefs, tick)
        if (f != null && prefs.videoChoice == VideoPolicy.ALL) sp.edit().putInt("tick", tick + 1).apply()
        return f
    }

    private fun resolve(ctx: Context, prefs: ShelfPrefs, tick: Int): File? {
        val monet = monet(ctx)
        val users = userVideos(ctx)
        return when (val t = VideoPolicy.pick(prefs.videoChoice, users.map { it.name }, monet != null, tick)) {
            null -> null
            VideoPolicy.MONET -> monet
            else -> users.firstOrNull { it.name == t }?.file
        }
    }
}
