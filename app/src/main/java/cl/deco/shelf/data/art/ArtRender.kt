package cl.deco.shelf.data.art

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Composición de imágenes a 1920x1080 compartida por la colección curada y "Mis fondos". */
object ArtRender {

    fun scaleTo(b: Bitmap, w: Int, h: Int): Bitmap {
        if (b.width == w && b.height == h) return b
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (out !== b) b.recycle()
        return out
    }

    /** Passe-partout en CPU: papel, sombra del bisel y sombra interior (equivale a mat169 de art/tools/process.py). */
    fun composeMat(art: Bitmap): Bitmap {
        val W = ArtCatalog.OUT_W
        val H = ArtCatalog.OUT_H
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(0xED, 0xE8, 0xE0))
        val x = (W - art.width) / 2
        val y = (H - art.height) / 2
        // sombra del bisel: caída suave bajo la obra
        val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 60, 52, 44)
            maskFilter = BlurMaskFilter(14.7f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRect(x.toFloat(), (y + 3).toFloat(), (x + art.width).toFloat(), (y + art.height + 3).toFloat(), sh)
        // sombra interior sobre la obra: anillo difuminado dentro de sus bordes
        val a = art.copy(Bitmap.Config.ARGB_8888, true)
        art.recycle()
        val ac = Canvas(a)
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(-30f, -30f, a.width + 30f, a.height + 30f), Path.Direction.CW)
            addRect(RectF(8f, 10f, a.width - 8f, a.height - 6f), Path.Direction.CW)
        }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(77, 20, 16, 12)
            maskFilter = BlurMaskFilter(11.3f, BlurMaskFilter.Blur.NORMAL)
        }
        ac.drawPath(ring, inner)
        c.drawBitmap(a, x.toFloat(), y.toFloat(), null)
        a.recycle()
        return out
    }

    /** Llena 1920x1080 en modo COVER con recorte centrado (sin saliencia: la colección curada trae sus recortes precalculados). Recicla [src]. */
    fun cover(src: Bitmap): Bitmap {
        val r = ImportMath.coverRect(src.width, src.height)
        val out = Bitmap.createBitmap(ArtCatalog.OUT_W, ArtCatalog.OUT_H, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, Rect(r.left, r.top, r.right, r.bottom), Rect(0, 0, ArtCatalog.OUT_W, ArtCatalog.OUT_H),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        src.recycle()
        return out
    }

    /** Obra completa dentro del passe-partout (para verticales y cuadradas). Recicla [src]. */
    fun mat(src: Bitmap): Bitmap {
        val (aw, ah) = ArtCatalog.matArtSize(src.width, src.height)
        return composeMat(scaleTo(src, aw, ah))
    }
}
