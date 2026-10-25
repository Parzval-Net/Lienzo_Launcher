package cl.deco.shelf.data.art

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Archivo de imagen o video visible en MediaStore (almacenamiento interno o USB). */
data class MediaItem(
    val uri: Uri,
    val volume: String,
    val name: String,
    val size: Long,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val isVideo: Boolean,
    val dateAdded: Long,
) {
    val key: String get() = ImportMath.dedupeKey(name, size)
    val isUsb: Boolean get() = volume != MediaStore.VOLUME_EXTERNAL_PRIMARY
}

/**
 * Importa imágenes y videos desde MediaStore a las carpetas propias de la app (`art_user`, `video_user`).
 * Solo lectura de MediaStore con el permiso estándar de medios; no abre red ni expone nada. Bloqueante: llamar desde un hilo propio.
 */
class MediaImporter(context: Context, private val art: UserArt = UserArt(context)) {

    private val app = context.applicationContext
    private val cr get() = app.contentResolver

    class Progress(val phase: String, val index: Int, val total: Int, val name: String, val doneBytes: Long, val totalBytes: Long)

    class Outcome(
        val images: Int = 0,
        val videos: Int = 0,
        /** (archivo, motivo) de videos rechazados por formato. */
        val rejected: List<Pair<String, String>> = emptyList(),
        val skipped: List<String> = emptyList(),
        val failed: List<Pair<String, String>> = emptyList(),
        val cancelled: Boolean = false,
        val noSpace: SpaceRules.Check? = null,
    )

    // ---- Consulta ----

    fun query(limit: Int = MAX_ITEMS): List<MediaItem> {
        val all = ArrayList<MediaItem>()
        val volumes = try { MediaStore.getExternalVolumeNames(app) } catch (e: Exception) { emptySet() }
        for (vol in volumes) {
            for (video in listOf(false, true)) {
                try { all += queryVolume(vol, video) } catch (e: Exception) { Log.w(TAG, "volumen $vol: $e") }
            }
        }
        return all.sortedByDescending { it.dateAdded }.take(limit)
    }

    private fun queryVolume(vol: String, video: Boolean): List<MediaItem> {
        val base = if (video) MediaStore.Video.Media.getContentUri(vol) else MediaStore.Images.Media.getContentUri(vol)
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        if (video) cols += MediaStore.MediaColumns.DURATION
        val out = ArrayList<MediaItem>()
        cr.query(base, cols.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(3).orEmpty()
                val size = c.getLong(2)
                val name = c.getString(1).orEmpty()
                if (size <= 0 || name.isEmpty()) continue
                // Solo formatos que no usan el decodificador de hardware (HEIC/HEIF quedan fuera)
                if (!video && mime !in IMAGE_MIMES) continue
                if (video && !mime.startsWith("video/")) continue
                out += MediaItem(
                    ContentUris.withAppendedId(base, c.getLong(0)), vol, name, size, mime,
                    c.getInt(4), c.getInt(5), if (video) c.getLong(7) else 0L, video, c.getLong(6),
                )
            }
        }
        return out
    }

    // ---- Validación de video ----

    /** Lee solo el contenedor (MediaExtractor, sin decodificar) y aplica [VideoRules]. */
    fun validateVideo(item: MediaItem): VideoRules.Verdict {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(app, item.uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return VideoRules.evaluate(null, 0, 0)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            val w = fmt.intOr(MediaFormat.KEY_WIDTH, 0)
            val h = fmt.intOr(MediaFormat.KEY_HEIGHT, 0)
            val rot = fmt.intOr(MediaFormat.KEY_ROTATION, 0)
            var fps = fmt.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f
            if (fps <= 0f) fps = estimateFps(ex, track)
            val ct = fmt.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
            val hdr = ct == MediaFormat.COLOR_TRANSFER_ST2084 || ct == MediaFormat.COLOR_TRANSFER_HLG
            // Consulta de catálogo de códecs: no instancia ningún decodificador
            var decoder = true
            if (VideoRules.isAvc(mime) || VideoRules.isHevc(mime)) {
                val q = MediaFormat.createVideoFormat(mime!!, w, h)
                fmt.getNumber(MediaFormat.KEY_PROFILE)?.let { q.setInteger(MediaFormat.KEY_PROFILE, it.toInt()) }
                decoder = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(q) != null } catch (_: Exception) { true }
            }
            return VideoRules.evaluate(mime, w, h, rot, fps, hdr, decoder)
        } catch (e: Exception) {
            Log.w(TAG, "validación de ${item.name}: $e")
            return VideoRules.evaluate(null, 0, 0)
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun MediaFormat.intOr(key: String, d: Int) = if (containsKey(key)) getNumber(key)?.toInt() ?: d else d

    /** fps por conteo de muestras del primer segundo (el contenedor no la declara). */
    private fun estimateFps(ex: MediaExtractor, track: Int): Float {
        ex.selectTrack(track)
        var n = 0; var tMin = Long.MAX_VALUE; var tMax = Long.MIN_VALUE
        while (n < 600) {
            val t = ex.sampleTime
            if (t < 0) break
            tMin = minOf(tMin, t); tMax = maxOf(tMax, t); n++
            if (tMax - tMin >= 1_000_000L) break
            if (!ex.advance()) break
        }
        return if (n >= 2 && tMax > tMin) (n - 1) * 1_000_000f / (tMax - tMin) else 0f
    }

    // ---- Espacio ----

    fun freeBytes(): Long {
        val d = art.targetDir() ?: app.getExternalFilesDir(null) ?: return 0L
        return try { StatFs(d.path).availableBytes } catch (_: Exception) { 0L }
    }

    // ---- Importación ----

    fun run(items: List<MediaItem>, cancel: AtomicBoolean, onProgress: (Progress) -> Unit): Outcome {
        val rejected = ArrayList<Pair<String, String>>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()

        // 1) Verificación de formato y duplicados
        val doneKeys = art.importedKeys()
        val videoDir = VideoLibrary.userDir(app)?.also { it.mkdirs() }
        val existingVideos = VideoLibrary.userVideos(app).associate { it.name to it.length() }
        val images = ArrayList<MediaItem>()
        val videos = ArrayList<MediaItem>()
        items.forEachIndexed { i, it ->
            if (cancel.get()) return Outcome(rejected = rejected, skipped = skipped, cancelled = true)
            onProgress(Progress("Verificando", i + 1, items.size, it.name, 0, 0))
            if (!it.isVideo) {
                if (it.key in doneKeys) skipped += it.name else images += it
            } else {
                // Se compara con el nombre ya saneado (así se guardó): antes "Clip (1).MP4" nunca coincidía y se copiaba otra vez
                val stored = ImportMath.safeFileName(it.name, emptySet())
                if (existingVideos[stored] == it.size || existingVideos[it.name] == it.size) { skipped += it.name; return@forEachIndexed }
                when (val v = validateVideo(it)) {
                    is VideoRules.Verdict.Ok -> videos += it
                    is VideoRules.Verdict.Rejected -> rejected += it.name to v.message
                }
            }
        }

        // 2) Espacio: se deja siempre un margen libre
        val total = SpaceRules.estimate(images.size, videos.sumOf { it.size })
        val space = SpaceRules.check(total, freeBytes())
        if (!space.ok) return Outcome(rejected = rejected, skipped = skipped, noSpace = space)

        // 3) Copia con progreso por bytes (cancelable)
        var done = 0L
        var imgs = 0; var vids = 0
        val todo = images.size + videos.size
        var idx = 0
        for (it in images) {
            if (cancel.get()) return Outcome(imgs, vids, rejected, skipped, failed, cancelled = true)
            onProgress(Progress("Importando", ++idx, todo, it.name, done, total))
            try {
                importImage(it)
                imgs++
            } catch (e: Throwable) {
                Log.w(TAG, "imagen ${it.name}: $e")
                failed += it.name to (if (e is IOException) "No se pudo guardar (espacio o lectura)" else "No se pudo decodificar")
            }
            done += SpaceRules.EST_IMAGE_BYTES
        }
        for (it in videos) {
            if (cancel.get()) return Outcome(imgs, vids, rejected, skipped, failed, cancelled = true)
            val base = done
            onProgress(Progress("Importando", ++idx, todo, it.name, base, total))
            try {
                val ok = copyVideo(it, videoDir ?: throw IOException("sin carpeta"), cancel) { n -> onProgress(Progress("Importando", idx, todo, it.name, base + n, total)) }
                if (ok) vids++ else return Outcome(imgs, vids, rejected, skipped, failed, cancelled = true)
            } catch (e: Throwable) {
                Log.w(TAG, "video ${it.name}: $e")
                failed += it.name to "No se pudo copiar (espacio o lectura)"
            }
            done = base + it.size
        }
        return Outcome(imgs, vids, rejected, skipped, failed)
    }

    private fun importImage(item: MediaItem) {
        var orientation = ExifInterface.ORIENTATION_NORMAL
        if (item.mime == "image/jpeg") {
            try { cr.openInputStream(item.uri)?.use { orientation = ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } } catch (_: Exception) {}
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream devuelve null con inJustDecodeBounds: se confirma la apertura aparte
        val stream = cr.openInputStream(item.uri) ?: throw IOException("sin lectura")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("no es una imagen")
        val rot = ImportMath.exifRotation(orientation)
        val (ow, oh) = if (rot % 180 == 90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        // Verticales y cuadradas solo llenan el passe-partout (alto <= 1080): pedir 1920x1080 en ambos ejes las decodificaba
        // completas (una foto de 12 MP vertical son ~48 MB, más otro tanto al rotar) y en un equipo de 2 GB terminaba en OOM
        var sample = if (ImportMath.needsMat(ow, oh)) ImportMath.sampleSize(ow, oh, 1, ArtCatalog.OUT_H) else ImportMath.sampleSize(ow, oh)
        var bmp: Bitmap? = null
        for (attempt in 0 until 3) {
            try {
                bmp = cr.openInputStream(item.uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }
                break
            } catch (_: OutOfMemoryError) { sample *= 2 }
        }
        var src = bmp ?: throw IllegalStateException("decodificación fallida")
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            val rotated = try {
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            } catch (_: OutOfMemoryError) {
                // Sin memoria para la copia rotada: se rota a la mitad de tamaño (sigue sobrando para 1080p)
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m.apply { postScale(0.5f, 0.5f) }, true)
            }
            if (rotated !== src) src.recycle()
            src = rotated
        }
        val mat = ImportMath.needsMat(src.width, src.height)
        val out = if (mat) ArtRender.mat(src) else ArtRender.cover(src)
        val id = art.newId()
        val dir = art.targetDir() ?: throw IOException("sin carpeta")
        val dest = File(dir, "$id.jpg")
        val part = File(dir, "$id.jpg.part")
        try {
            part.outputStream().use { if (!out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) throw IOException("JPEG") }
            if (!part.renameTo(dest)) throw IOException("rename")
        } finally {
            out.recycle()
            part.delete()
        }
        art.add(
            UserArt.Entry(id, item.name.substringBeforeLast('.'), dest, dest.length(), home = !mat, key = item.key),
            if (mat) "mat" else "cover",
        )
    }

    /** Copia el video a video_user. Devuelve false si se canceló (el parcial se borra). */
    private fun copyVideo(item: MediaItem, dir: File, cancel: AtomicBoolean, progress: (Long) -> Unit): Boolean {
        val name = ImportMath.safeFileName(item.name, dir.list()?.toSet().orEmpty())
        val dest = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            val input = cr.openInputStream(item.uri) ?: throw IOException("sin lectura")
            input.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var n = 0L
                    var lastTick = 0L
                    while (true) {
                        if (cancel.get()) return false
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 200) { lastTick = now; progress(n) }
                    }
                    progress(n)
                }
            }
            if (!part.renameTo(dest)) throw IOException("rename")
            return true
        } finally {
            if (part.exists()) part.delete()
        }
    }

    companion object {
        private const val TAG = "MediaImporter"
        private const val JPEG_QUALITY = 85
        private const val MAX_ITEMS = 1000
        private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp", "image/bmp")

        /** Permiso estándar de medios del deco (Android 12): READ_EXTERNAL_STORAGE. */
        fun permissions(): Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        fun hasPermission(ctx: Context) = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
