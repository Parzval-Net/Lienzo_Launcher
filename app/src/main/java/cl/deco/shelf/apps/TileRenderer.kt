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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normaliza el banner o ícono ACTUAL de cada app a un tile 16:9 homogéneo (w x h px, esquinas redondeadas ya aplicadas).
 * Siempre parte del arte instalado, así una actualización de la app trae su arte nuevo. Pipeline:
 * - Banner opaco 16:9: escala exacta al tile desde su mayor resolución, sin trim, relleno ni crop (idéntico al oficial).
 * - Banner con transparencia: logo recortado por alpha, centrado con 8% de padding, sobre su color promedio oscurecido.
 * - Solo ícono adaptive: foreground al 60% del alto sobre degradado diagonal de su background. Legacy: 50% con sombra.
 * - Overrides artesanales por paquete (IconOverrides) solo para apps sin arte propio. Nunca para marcas.
 * Acabado: esquinas redondeadas y borde interior de 1 px blanco al 6% (el brillo especular solo en el tile enfocado).
 * Cache en disco cacheDir/tiles/{pkg}_{versionCode}_{hash}.png: la clave incluye versionCode, una actualización
 * con Shelf cerrado se detecta sola al arrancar. Bloqueante: llamar fuera del main thread.
 */
class TileRenderer(private val context: Context, private val w: Int, private val h: Int, private val radius: Float) {

    enum class Kind { BANNER, ADAPTIVE, LEGACY, OVERRIDE, LOGO }

    private val pm = context.packageManager
    private val dir = File(context.cacheDir, "tiles")

    /** Último tipo usado por clave de app (solo para diagnóstico y reporte). */
    val kinds = java.util.concurrent.ConcurrentHashMap<String, Kind>()

    private var purged = false

    fun tile(entry: AppEntry, original: Boolean): Bitmap? {
        if (!purged) {
            purged = true
            // Formato de cache nuevo: borra los tiles de revisiones anteriores
            dir.listFiles()?.filter { !it.name.contains("_r$RENDER_REV") }?.forEach { it.delete() }
        }
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
        return File(dir, "${pkg}_${ver}_${hash}_r$RENDER_REV${if (original) "_o" else ""}.png")
    }

    private fun render(entry: AppEntry, original: Boolean): Bitmap? {
        val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        val kind: Kind
        val override = IconOverrides.forComponent(entry.component)?.let { context.getDrawable(it) }
        val logo = IconOverrides.officialLogo(entry.component.packageName)
        if (logo != null && !original && drawOfficialLogo(c, entry.component.packageName, logo)) {
            kind = Kind.LOGO
        } else if (override != null) {
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

    // ---- Logo oficial resuelto en runtime desde la app instalada ----

    /**
     * Fondo con degradado diagonal del color de marca de la app (18% más oscuro al final) y su logo vectorial oficial
     * centrado al 62% del ancho, dibujado directo a la resolución final. Ambos recursos se buscan por nombre en la app
     * instalada: sobrevive a las actualizaciones y no redibuja la marca. Si algo falta devuelve false (cae al banner).
     */
    private fun drawOfficialLogo(c: Canvas, pkg: String, spec: IconOverrides.OfficialLogo): Boolean = try {
        val pctx = context.createPackageContext(pkg, 0)
        val res = pctx.resources
        val did = res.getIdentifier(spec.drawable, "drawable", pkg)
        val d = if (did != 0) res.getDrawable(did, pctx.theme) else null
        if (d == null) false else {
            val cid = res.getIdentifier(spec.color, "color", pkg)
            val base = if (cid != 0) res.getColor(cid, pctx.theme) or 0xFF000000.toInt() else spec.fallback
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), base, mix(base, Color.BLACK, 0.18f), Shader.TileMode.CLAMP)
            })
            val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 148
            val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 64
            val dw = (w * 0.62f).toInt()
            val dh = (dw * ih / iw.toFloat()).toInt()
            val l = (w - dw) / 2; val t = (h - dh) / 2
            d.setBounds(l, t, l + dw, t + dh); d.draw(c)
            true
        }
    } catch (e: Throwable) { Log.w(TAG, "logo oficial $pkg: $e"); false }

    // ---- Banner ----

    /**
     * Tiles 16:9 (formato nativo de los banners de Android TV). Banner opaco (< 2% de píxeles con alpha < 250) y de
     * aspecto cercano a 16:9: se dibuja completo a escala exacta, sin crop, sin relleno y sin padding, desde su mayor
     * resolución (nunca escala hacia arriba para luego bajar). Banner con transparencia o de otro aspecto: logo
     * recortado por alpha, centrado con 8% de padding sobre el color promedio de sus píxeles opacos oscurecido 35%.
     */
    private fun drawBanner(c: Canvas, banner: Drawable) {
        val bmp = (banner as? BitmapDrawable)?.bitmap
        val iw = bmp?.width ?: banner.intrinsicWidth
        val ih = bmp?.height ?: banner.intrinsicHeight
        val ar = if (iw > 0 && ih > 0) iw / ih.toFloat() else 16f / 9f
        val opaque = if (bmp != null) !bmp.hasAlpha() || opaqueShare(bmp) >= 0.98f else opaqueShare(rasterize(banner, 128, 72)) >= 0.98f
        if (opaque && ar in 1.6f..1.95f) exactBanner(c, banner, bmp) else logoOnColor(c, banner)
    }

    private fun exactBanner(c: Canvas, banner: Drawable, bmp: Bitmap?) {
        if (bmp != null) {
            // Origen mucho mayor que el tile: reducir a la mitad antes para no aliasar
            var src: Bitmap = bmp; var owned = false
            while (w / src.width.toFloat() < 0.5f) {
                val half = Bitmap.createScaledBitmap(src, max(1, src.width / 2), max(1, src.height / 2), true)
                if (owned) src.recycle()
                src = half; owned = true
            }
            c.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            if (owned) src.recycle()
        } else {
            // Vector u otro drawable: directo a la resolución final
            banner.setBounds(0, 0, w, h); banner.draw(c)
        }
    }

    private fun logoOnColor(c: Canvas, banner: Drawable) {
        val src = toBitmap(banner)
        val px = IntArray(EW * EH)
        val sm = Bitmap.createScaledBitmap(src, EW, EH, true)
        sm.getPixels(px, 0, EW, 0, 0, EW, EH)
        if (sm !== src) sm.recycle()
        c.drawColor(mix(dominant(px), Color.BLACK, 0.35f))
        val box = contentBox(src) ?: Rect(0, 0, src.width, src.height)
        val cw = box.width(); val ch = box.height()
        val s = min(w * (1f - 2 * PAD) / cw, h * (1f - 2 * PAD) / ch).coerceAtMost(MAX_UP)
        val dw = cw * s; val dh = ch * s
        val l = (w - dw) / 2f; val t = (h - dh) / 2f
        c.drawBitmap(src, box, RectF(l, t, l + dw, t + dh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
    }

    /** Fracción de píxeles (muestreo en grilla de 64x36) con alpha >= 250. */
    private fun opaqueShare(b: Bitmap): Float {
        var n = 0; var ok = 0
        for (gy in 0 until 36) for (gx in 0 until 64) {
            val p = b.getPixel(gx * (b.width - 1) / 63, gy * (b.height - 1) / 35)
            n++; if (Color.alpha(p) >= 250) ok++
        }
        return ok / n.toFloat()
    }

    private fun rasterize(d: Drawable, rw: Int, rh: Int): Bitmap {
        val b = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        val old = d.copyBounds()
        d.setBounds(0, 0, rw, rh); d.draw(Canvas(b)); d.bounds = old
        return b
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

    /** Caja de contenido por alpha (sin usar color de borde). Null si no hay o es casi todo transparente. */
    private fun contentBox(src: Bitmap): Rect? {
        val sw = src.width; val sh = src.height
        val px = IntArray(sw * sh)
        src.getPixels(px, 0, sw, 0, 0, sw, sh)
        var x0 = sw; var y0 = sh; var x1 = -1; var y1 = -1
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until sw) {
                if (Color.alpha(px[row + x]) < ALPHA_MIN) continue
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x1 < x0 || y1 < y0) return null
        return Rect(x0, y0, x1 + 1, y1 + 1)
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
        gradient(c, mix(averageColor(icon), Color.BLACK, if (original) 0f else 0.35f))
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
            // Solo borde interior de 1 px blanco al 6%; el brillo especular vive únicamente en el tile enfocado
            c.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x0FFFFFFF
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
        const val RENDER_REV = 5 // subir al cambiar el algoritmo: invalida los tiles en disco
        const val EW = 32
        const val EH = 18
        const val UNIFORM_SHARE = 0.6f
        const val TOL = 28
        const val ALPHA_MIN = 24
        const val PAD = 0.08f
        const val MAX_UP = 4f
    }
}
