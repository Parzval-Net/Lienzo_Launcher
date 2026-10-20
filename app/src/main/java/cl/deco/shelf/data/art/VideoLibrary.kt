package cl.deco.shelf.data.art

import android.content.Context
import cl.deco.shelf.settings.ShelfPrefs
import java.io.File

/**
 * Videos del modo arte y del protector: el de Monet (`getExternalFilesDir("video")`, cargado por adb) y los propios
 * (`getExternalFilesDir("video_user")`). La selección vive en [ShelfPrefs.videoChoice] y es común a ambos usos.
 */
object VideoLibrary {
    const val USER_DIR = "video_user"
    private const val TICK_PREFS = "video_library"

    fun monet(ctx: Context): File? =
        ctx.getExternalFilesDir("video")?.listFiles { f -> f.extension.equals("mp4", true) }?.minByOrNull { it.name }

    fun userDir(ctx: Context): File? = ctx.getExternalFilesDir(USER_DIR)

    fun userVideos(ctx: Context): List<File> =
        userDir(ctx)?.listFiles { f -> f.isFile && f.extension.lowercase() in VideoRules.EXTENSIONS && f.length() > 0 }
            ?.sortedBy { it.name.lowercase() }.orEmpty()

    fun delete(ctx: Context, name: String): Boolean {
        val f = userVideos(ctx).firstOrNull { it.name == name } ?: return false
        return f.delete()
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
            else -> users.firstOrNull { it.name == t }
        }
    }
}
