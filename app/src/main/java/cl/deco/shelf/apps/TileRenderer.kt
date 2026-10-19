package cl.deco.shelf.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normaliza el banner o ícono ACTUAL de cada app a un tile 5:3 homogéneo (w x h px, esquinas redondeadas ya aplicadas).
 * Siempre parte del arte instalado, así una actualización de la app trae su arte nuevo. Pipeline:
 * - Banner con borde uniforme (moda del perímetro en 32x18 o borde transparente): recorta márgenes (alpha y color de
 *   borde con tolerancia), recentra con 8% de padding y rellena con el color de borde.
 * - Banner a sangre (borde no uniforme): cubre el tile (crop centrado) si el aspecto es parecido, si no contiene.
 * - Solo ícono adaptive: foreground al 60% del alto sobre degradado diagonal de su background. Legacy: 50% con sombra.
 * - Overrides artesanales por paquete (IconOverrides) solo para apps sin arte propio. Nunca para marcas.
 * Acabado: brillo especular arriba (blanco 0 a 12%) y borde interior de 1 px blanco al 8%.
 * Cache en disco cacheDir/tiles/{pkg}_{versionCode}_{hash}.png: la clave incluye versionCode, una actualización
 * con Shelf cerrado se detecta sola al arrancar. Bloqueante: llamar fuera del main thread.
 */
class TileRenderer(private val context: Context, private val w: Int, private val h: Int, private val radius: Float) {

    enum class Kind { BANNER, ADAPTIVE, LEGACY, OVERRIDE }

    private val pm = context.packageManager
    private val dir = File(context.cacheDir, "tiles")

    /** Último tipo usado por clave de app (solo para diagnóstico y reporte). */
    val kinds = java.util.concurrent.ConcurrentHashMap<String, Kind>()

    fun tile(entry: AppEntry, original: Boolean): Bitmap? {
        val f = cacheFile(entry, original)
        if (f != null && f.exists()) {
            BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val bmp = try { render(entry, original) } catch (e: Throwable) { Log.w(TAG, "render ${entry.key}: $e"); null } ?: return null
        if (f != null) try {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(f)
        } catch (e: Throwable) { Log.w(TAG, "cache ${entry.key}: $e") }
        return bmp
    }

    /** Borra los tiles cacheados de un paquete (al reemplazarse o desinstalarse). */
    fun invalidate(pkg: String) {
        dir.listFiles()?.filter { it.name.startsWith(pkg + "_") }?.forEach { it.delete() }
    }

    fun clearAll() { dir.listFiles()?.forEach { it.delete() } }

    private fun cacheFile(entry: AppEntry, original: Boolean): File? {
        val pkg = entry.component.packageName
        val ver = try { pm.getPackageInfo(pkg, 0).longVersionCode } catch (_: PackageManager.NameNotFoundException) { return null }
        val hash = Integer.toHexString(entry.component.className.hashCode())
        return File(dir, "${pkg}_${ver}_$hash${if (original) "_o" else ""}.png")
    }

    private fun render(entry: AppEntry, original: Boolean): Bitmap? {
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        val kind: Kind
        val override = IconOverrides.forComponent(entry.component)?.let { context.getDrawable(it) }
        if (override != null) {
            override.setBounds(0, 0, w, h); override.draw(c)
            kind = Kind.OVERRIDE
        } else {
            val info = pm.getActivityInfo(entry.component, 0)
            val banner = info.loadBanner(pm)
            kind = if (banner != null) {
                if (original) drawBannerOriginal(c, banner) else drawBanner(c, banner)
                Kind.BANNER
            } else {
                val icon = info.loadIcon(pm)
                val adaptive = icon as? AdaptiveIconDrawable
                if (adaptive != null) drawAdaptive(c, adaptive, original) else drawLegacy(c, icon, original)
                if (adaptive != null) Kind.ADAPTIVE else Kind.LEGACY
            }
        }
        kinds[entry.key] = kind
        Log.i(TAG, "tile ${entry.key} -> $kind${if (original) " (original)" else ""}")
        return finish(flat, shine = !original)
    }

    // ---- Banner ----

    private fun drawBanner(c: Canvas, banner: Drawable) {
        val src = toBitmap(banner)
        val px = IntArray(EW * EH)
        val sm = Bitmap.createScaledBitmap(src, EW, EH, true)
        sm.getPixels(px, 0, EW, 0, 0, EW, EH)
        if (sm !== src) sm.recycle()
        val edge = edgeColor(px)
        val uniform = edge.transparent || edge.share >= UNIFORM_SHARE
        if (uniform && !edge.transparent) {
            c.drawColor(edge.color)
            val box = contentBox(src, edge.color) ?: Rect(0, 0, src.width, src.height)
            val cw = box.width(); val ch = box.height()
            val s = min(w * (1f - 2 * PAD) / cw, h * (1f - 2 * PAD) / ch).coerceAtMost(MAX_UP)
            val dw = cw * s; val dh = ch * s
            val l = (w - dw) / 2f; val t = (h - dh) / 2f
            c.drawBitmap(src, box, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else if (edge.transparent) {
            // Logo sobre transparente: degradado del color dominante y logo recortado al 8% de padding
            val base = dominant(px)
            gradient(c, base)
            val box = contentBox(src, null) ?: Rect(0, 0, src.width, src.height)
            val cw = box.width(); val ch = box.height()
            val s = min(w * (1f - 2 * PAD) / cw, h * (1f - 2 * PAD) / ch).coerceAtMost(MAX_UP)
            val dw = cw * s; val dh = ch * s
            val l = (w - dw) / 2f; val t = (h - dh) / 2f
            c.drawBitmap(src, box, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            // Arte a sangre: cover si el aspecto es cercano a 5:3, si no contain sobre el color dominante
            val ar = src.width / src.height.toFloat()
            val cover = ar in 1.45f..2.05f
            val s = if (cover) max(w / src.width.toFloat(), h / src.height.toFloat()) else min(w / src.width.toFloat(), h / src.height.toFloat())
            val dw = src.width * s; val dh = src.height * s
            if (!cover) c.drawColor(dominant(px))
            val l = (w - dw) / 2f; val t = (h - dh) / 2f
            c.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
    }

    private fun drawBannerOriginal(c: Canvas, banner: Drawable) {
        val iw = banner.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = banner.intrinsicHeight.takeIf { it > 0 } ?: h
        c.drawColor(averageColor(banner))
        val s = min(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * s).toInt(); val dh = (ih * s).toInt()
        val l = (w - dw) / 2; val t = (h - dh) / 2
        banner.setBounds(l, t, l + dw, t + dh); banner.draw(c)
    }

    /** Banner a bitmap ARGB con lado mayor entre 440 y 640 px (vectores y bitmaps chicos se re-rasterizan). */
    private fun toBitmap(d: Drawable): Bitmap {
        var iw = d.intrinsicWidth.takeIf { it > 0 } ?: 640
        var ih = d.intrinsicHeight.takeIf { it > 0 } ?: 360
        val k = when { iw > 640 -> 640f / iw; iw < 440 -> 440f / iw; else -> 1f }
        iw = (iw * k).toInt().coerceAtLeast(1); ih = (ih * k).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, iw, ih); d.draw(Canvas(b))
        return b
    }

    private class Edge(val color: Int, val share: Float, val transparent: Boolean)

    /** Moda (cubos de 8 niveles por canal) de los píxeles del perímetro de la versión 32x18. */
    private fun edgeColor(px: IntArray): Edge {
        val counts = HashMap<Int, Int>()
        val sums = HashMap<Int, IntArray>()
        var transparent = 0; var total = 0
        fun add(i: Int) {
            total++
            val p = px[i]
            if (Color.alpha(p) < 128) { transparent++; return }
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val k = (r shr 5 shl 6) or (g shr 5 shl 3) or (b shr 5)
            counts[k] = (counts[k] ?: 0) + 1
            val s = sums.getOrPut(k) { IntArray(3) }
            s[0] += r; s[1] += g; s[2] += b
        }
        for (x in 0 until EW) { add(x); add((EH - 1) * EW + x) }
        for (y in 1 until EH - 1) { add(y * EW); add(y * EW + EW - 1) }
        if (transparent * 100 >= total * 60) return Edge(0, 1f, true)
        val best = counts.maxByOrNull { it.value } ?: return Edge(0, 1f, true)
        val s = sums.getValue(best.key); val n = best.value
        return Edge(Color.rgb(s[0] / n, s[1] / n, s[2] / n), best.value / total.toFloat(), false)
    }

    /** Caja de contenido: píxeles con alpha visible y distintos del color de borde (tolerancia). Null si no hay o es casi todo. */
    private fun contentBox(src: Bitmap, edge: Int?): Rect? {
        val sw = src.width; val sh = src.height
        val px = IntArray(sw * sh)
        src.getPixels(px, 0, sw, 0, 0, sw, sh)
        var x0 = sw; var y0 = sh; var x1 = -1; var y1 = -1
        val er = if (edge != null) Color.red(edge) else 0
        val eg = if (edge != null) Color.green(edge) else 0
        val eb = if (edge != null) Color.blue(edge) else 0
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until sw) {
                val p = px[row + x]
                if (Color.alpha(p) < ALPHA_MIN) continue
                if (edge != null && max(abs(Color.red(p) - er), max(abs(Color.green(p) - eg), abs(Color.blue(p) - eb))) <= TOL) continue
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x1 < x0 || y1 < y0) return null
        val box = Rect(x0, y0, x1 + 1, y1 + 1)
        if (box.width() * box.height() < 0.02f * sw * sh) return null
        return box
    }

    // ---- Solo ícono ----

    private fun drawAdaptive(c: Canvas, a: AdaptiveIconDrawable, original: Boolean) {
        val base = a.background?.let { averageColor(it) } ?: 0xFF2A2E3A.toInt()
        gradient(c, base)
        // La capa mide 108 dp con zona segura de 72: el 60% del alto se refiere a la zona visible
        val side = (h * (if (original) 0.55f else 0.60f) * 108f / 72f).toInt()
        val l = (w - side) / 2; val t = (h - side) / 2
        val fg = a.foreground ?: return
        fg.setBounds(l, t, l + side, t + side); fg.draw(c)
    }

    private fun drawLegacy(c: Canvas, icon: Drawable, original: Boolean) {
        gradient(c, averageColor(icon))
        val side = (h * if (original) 0.55f else 0.50f).toInt()
        val l = (w - side) / 2; val t = (h - side) / 2
        val tmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, side, side); icon.draw(Canvas(tmp))
        if (!original) {
            val off = IntArray(2)
            val shadow = tmp.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(side * 0.06f, BlurMaskFilter.Blur.NORMAL) }, off)
            c.drawBitmap(shadow, (l + off[0]).toFloat(), t + off[1] + side * 0.04f, Paint().apply { color = 0x66000000 })
            shadow.recycle()
        }
        c.drawBitmap(tmp, l.toFloat(), t.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        tmp.recycle()
    }

    private fun gradient(c: Canvas, base: Int) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), mix(base, Color.WHITE, 0.22f), mix(base, Color.BLACK, 0.40f), Shader.TileMode.CLAMP)
        })
    }

    // ---- Acabado ----

    private fun finish(flat: Bitmap, shine: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        flat.recycle()
        if (shine) {
            c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            c.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x14FFFFFF
            })
        }
        return out
    }

    // ---- Color ----

    /** Color dominante: promedio de los píxeles opacos de la miniatura. */
    private fun dominant(px: IntArray): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        return if (n == 0L) 0xFF1B1E27.toInt() else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun averageColor(d: Drawable): Int {
        val small = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        val old = d.copyBounds()
        d.setBounds(0, 0, 16, 9); d.draw(Canvas(small)); d.bounds = old
        val px = IntArray(16 * 9)
        small.getPixels(px, 0, 16, 0, 0, 16, 9); small.recycle()
        return dominant(px)
    }

    private fun mix(c: Int, other: Int, t: Float): Int = Color.rgb(
        (Color.red(c) + (Color.red(other) - Color.red(c)) * t).toInt(),
        (Color.green(c) + (Color.green(other) - Color.green(c)) * t).toInt(),
        (Color.blue(c) + (Color.blue(other) - Color.blue(c)) * t).toInt(),
    )

    private companion object {
        const val TAG = "ShelfTiles"
        const val EW = 32
        const val EH = 18
        const val UNIFORM_SHARE = 0.6f
        const val TOL = 28
        const val ALPHA_MIN = 24
        const val PAD = 0.08f
        const val MAX_UP = 4f
    }
}
