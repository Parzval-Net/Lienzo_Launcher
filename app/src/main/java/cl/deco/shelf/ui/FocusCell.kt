package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Celda con foco estilo tvOS: escala 1.1, sombra precalculada (drawable por estado), brillo especular
 * estático (horneado en el tile) y parallax del contenido según la dirección del D-pad. Solo animaciones de propiedades de View.
 * Tiles y tarjetas son RGB_565 opacos: [RoundImageView] redondea al dibujar con un shader, sin clipping.
 */
open class FocusCell(
    ctx: Context,
    private val cw: Int,
    private val ch: Int,
    private val padX: Int,
    private val padTop: Int,
    private val padBottom: Int,
    private val radius: Float,
    withLabel: Boolean = false,
) : FrameLayout(ctx) {

    val content = FrameLayout(ctx)
    val image: ImageView = RoundImageView(ctx, radius)
    private val halo = FxView(ctx)
    private val gloss = FxView(ctx)
    private val parallax = ctx.dpf(5f)
    private val ring: Drawable = GradientDrawable().apply {
        setColor(0)
        setStroke(ctx.dp(3), 0xFFFFFFFF.toInt())
        cornerRadius = radius
    }
    val label: android.widget.TextView? = if (withLabel) tv(ctx, 12f, Ui.TEXT, "sans-serif-medium").apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setShadowLayer(ctx.dpf(3f), 0f, ctx.dpf(1f), 0xCC000000.toInt())
        alpha = 0f
    } else null
    var onFocus: ((Boolean) -> Unit)? = null
    private var newTag: android.widget.TextView? = null

    /** Etiqueta "Nuevo" (pill de 12 sp con el acento) para apps instaladas y aún no abiertas. */
    fun setNew(on: Boolean) {
        if (!on) { newTag?.visibility = GONE; return }
        val t = newTag ?: tv(context, 12f, 0xFF111111.toInt(), "sans-serif-medium").apply {
            text = "Nuevo"
            setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
            content.addView(this, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = context.dp(4); rightMargin = context.dp(4) })
        }.also { newTag = it }
        t.background = GradientDrawable().apply { setColor(Theme.accent); cornerRadius = context.dpf(10f) }
        t.visibility = VISIBLE
    }

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        layoutParams = RecyclerView.LayoutParams(cw + 2 * padX, ch + padTop + padBottom)
        // Halo de foco: un bitmap compartido por geometría, teñido con el acento (sin layouts al cambiar el foco)
        halo.background = HaloBitmaps.drawable(ctx, cw + 2 * padX, ch + padTop + padBottom, padX, padTop, padBottom, radius)
        halo.alpha = 0f
        addView(halo, LayoutParams(-1, -1))
        content.clipChildren = false
        content.addView(image, LayoutParams(-1, -1))
        // Brillo especular solo con foco: blanco 0 a 10% (el tile en reposo no lo lleva)
        gloss.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x1AFFFFFF, 0x00FFFFFF)).apply { cornerRadius = radius }
        gloss.alpha = 0f
        content.addView(gloss, LayoutParams(-1, -1))
        addView(content, LayoutParams(cw, ch, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = padTop })
        label?.let { addView(it, LayoutParams(cw + padX, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = padTop + ch + ctx.dp(6) }) }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        gloss.animate().alpha(if (gainFocus) 1f else 0f).setDuration(Ui.FOCUS_MS).start()
        if (gainFocus) halo.background?.setTint(Theme.accent)
        halo.animate().alpha(if (gainFocus) 1f else 0f).setDuration(Ui.FOCUS_MS).start()
        val s = if (gainFocus) Ui.FOCUS_SCALE else 1f
        animate().scaleX(s).scaleY(s).setDuration(Ui.FOCUS_MS).setInterpolator(Ui.DECEL).start()
        val p = if (gainFocus) parallax else 0f
        content.animate().translationX(Nav.dx * p).translationY(Nav.dy * p)
            .setDuration(Ui.FOCUS_MS).setInterpolator(Ui.DECEL).start()
        label?.animate()?.alpha(if (gainFocus) 1f else 0f)?.setDuration(Ui.FOCUS_MS)?.start()
        onFocus?.invoke(gainFocus)
    }

    fun setMoving(on: Boolean) {
        content.foreground = if (on) ring else null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            event.startTracking()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            return performLongClick()
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isTracking && !event.isCanceled) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                performClick()
                return true
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            return performLongClick()
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Estado limpio al reciclar la vista. */
    fun resetFx() {
        animate().cancel()
        content.animate().cancel()
        halo.animate().cancel()
        gloss.animate().cancel()
        gloss.alpha = 0f
        halo.alpha = 0f
        label?.animate()?.cancel()
        label?.alpha = 0f
        scaleX = 1f
        scaleY = 1f
        content.translationX = 0f
        content.translationY = 0f
        content.foreground = null
    }
}

/** Bitmaps de halo (máscara ALPHA_8 difusa) cacheados por tamaño; cada celda usa un BitmapDrawable que comparte el bitmap. */
object HaloBitmaps {
    private val map = HashMap<String, android.graphics.Bitmap>()

    fun drawable(ctx: Context, w: Int, h: Int, padX: Int, padTop: Int, padBottom: Int, radius: Float): Drawable {
        val key = "$w,$h,$padX,$padTop"
        val bmp = map.getOrPut(key) {
            // Solo alfa (1 B/px en vez de 4): el color lo pone setTint con el acento
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99FFFFFF.toInt()
                maskFilter = android.graphics.BlurMaskFilter(ctx.dpf(6f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            val shift = ctx.dpf(2f)
            android.graphics.Canvas(b).drawRoundRect(
                android.graphics.RectF(padX.toFloat(), padTop + shift, (w - padX).toFloat(), h - padBottom + shift),
                radius, radius, p,
            )
            b
        }
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }

    fun clear() { map.clear() }
}

/**
 * ImageView que redondea al dibujar los bitmaps opacos (tiles y tarjetas en RGB_565, la mitad de memoria que ARGB):
 * un BitmapShader con drawRoundRect cuesta lo mismo que dibujar el bitmap y no abre clipping ni capas.
 */
class RoundImageView(ctx: Context, private val radius: Float) : ImageView(ctx) {
    init { scaleType = ScaleType.FIT_XY }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        if (bm == null || bm.hasAlpha()) super.setImageBitmap(bm) else setImageDrawable(RoundBitmapDrawable(bm, radius))
    }
}

/** Bitmap estirado a sus bounds con esquinas redondeadas (antialias). */
class RoundBitmapDrawable(private val bmp: android.graphics.Bitmap, private val radius: Float) : Drawable() {
    private val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply { shader = this@RoundBitmapDrawable.shader }
    private val rect = android.graphics.RectF()
    private val m = android.graphics.Matrix()

    override fun onBoundsChange(b: android.graphics.Rect) {
        rect.set(b)
        m.setRectToRect(android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), rect, android.graphics.Matrix.ScaleToFit.FILL)
        shader.setLocalMatrix(m)
    }

    override fun draw(canvas: android.graphics.Canvas) { canvas.drawRoundRect(rect, radius, radius, paint) }
    override fun getIntrinsicWidth() = bmp.width
    override fun getIntrinsicHeight() = bmp.height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Vista de efecto (halo, brillo): sin renderizado superpuesto, así animar su alpha no abre una capa fuera de pantalla en la GPU. */
class FxView(ctx: Context) : View(ctx) {
    override fun hasOverlappingRendering() = false
}
