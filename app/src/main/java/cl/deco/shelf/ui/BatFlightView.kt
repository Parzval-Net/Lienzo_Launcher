package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Saludo de Halloween en el primer Home del día: una colonia de murciélagos sube desde abajo y sale por arriba
 * batiendo las alas. Capa transparente sin foco ni clics; no asigna memoria por cuadro y el bitmap se suelta al
 * terminar. Portado de BatFlightView de CleanYourTV (MIT); murciélago de Fluent Emoji 3D (MIT).
 */
class BatFlightView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private var bat: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val rnd = Random(SystemClock.uptimeMillis())

    private var count = 0
    private var x0 = FloatArray(0); private var drift = FloatArray(0); private var delay = FloatArray(0)
    private var life = FloatArray(0); private var size = FloatArray(0); private var beatHz = FloatArray(0)
    private var phase = FloatArray(0); private var weave = FloatArray(0); private var mirror = BooleanArray(0)

    private var playing = false
    private var start = 0L
    private var duration = 1L
    private var onDone: (() -> Unit)? = null
    private val finish = Runnable { finish() }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** Sube [bats] murciélagos durante [durationMs] y luego llama [done] en el hilo principal. */
    fun play(bats: Int, durationMs: Long, done: () -> Unit) {
        if (playing) { removeCallbacks(finish); finish() }
        if (bat == null) bat = BitmapFactory.decodeResource(resources, cl.deco.shelf.R.drawable.halloween_bat)
        val n = bats.coerceAtLeast(0)
        if (x0.size < n) {
            x0 = FloatArray(n); drift = FloatArray(n); delay = FloatArray(n); life = FloatArray(n); size = FloatArray(n)
            beatHz = FloatArray(n); phase = FloatArray(n); weave = FloatArray(n); mirror = BooleanArray(n)
        }
        count = n
        duration = durationMs.coerceAtLeast(1)
        onDone = done
        for (i in 0 until n) {
            x0[i] = (i + rnd.nextFloat()) / n               // repartidos a lo ancho
            drift[i] = (rnd.nextFloat() - 0.5f) * 0.5f      // fracción del ancho que se desplaza
            delay[i] = rnd.nextFloat() * 0.35f              // fracciones de la duración
            life[i] = 0.55f + rnd.nextFloat() * (1f - delay[i] - 0.55f)
            size[i] = 26f + rnd.nextFloat() * 30f           // dp
            beatHz[i] = 5f + rnd.nextFloat() * 3f
            phase[i] = rnd.nextFloat() * (2 * PI).toFloat()
            weave[i] = 8f + rnd.nextFloat() * 18f           // dp de zigzag
            mirror[i] = drift[i] < 0f                       // mira hacia donde va
        }
        playing = true
        start = SystemClock.uptimeMillis()
        postDelayed(finish, duration)
        postInvalidateOnAnimation()
    }

    private fun finish() {
        if (!playing) return
        playing = false
        invalidate()
        bat?.recycle(); bat = null
        val done = onDone; onDone = null
        done?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (playing) { removeCallbacks(finish); finish() }
    }

    override fun onDraw(c: Canvas) {
        val b = bat
        if (!playing || b == null) return
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - start) / duration.toFloat()
        val secs = (now - start) / 1000f
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0 until count) {
            val p = (elapsed - delay[i]) / life[i]
            if (p <= 0f || p >= 1f) continue
            val s = size[i] * density
            val rise = p * (0.6f + 0.4f * p)                // despega y luego acelera
            val y = h + s - (h + 2.5f * s) * rise
            val x = (x0[i] + drift[i] * p) * w + weave[i] * density * sin(2 * PI * 0.8 * secs + phase[i]).toFloat()
            val beat = sin(2 * PI * beatHz[i] * secs + phase[i]).toFloat()
            val sy = 0.62f + 0.38f * abs(beat)              // alas que se pliegan y abren
            paint.alpha = when {
                p < 0.12f -> (255 * p / 0.12f).toInt()
                p > 0.8f -> (255 * (1f - (p - 0.8f) / 0.2f)).toInt()
                else -> 255
            }
            c.save()
            c.translate(x, y)
            c.scale(if (mirror[i]) -1f else 1f, sy)
            dst.set(-s / 2f, -s / 2f, s / 2f, s / 2f)
            c.drawBitmap(b, null, dst, paint)
            c.restore()
        }
        postInvalidateOnAnimation()
    }
}
