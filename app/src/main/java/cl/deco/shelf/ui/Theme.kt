package cl.deco.shelf.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import cl.deco.shelf.util.Palette

/**
 * Colores de acento vigentes. Con "Dinámicos" salen de la obra de fondo (transición de 400 ms solo al cambiar de obra);
 * con "Neutros" son los fijos. Quien dibuja se suscribe en [listeners] y lee los campos.
 */
object Theme {
    const val NEUTRAL_ACCENT = 0xFFFF7A1A.toInt()
    const val NEUTRAL_LIGHT = 0xFF7DBBFF.toInt()
    const val NEUTRAL_DARK = 0xFF000000.toInt()

    @JvmStatic var accent = NEUTRAL_ACCENT; private set
    @JvmStatic var light = NEUTRAL_LIGHT; private set
    @JvmStatic var dark = NEUTRAL_DARK; private set
    var dynamic = true

    val listeners = ArrayList<() -> Unit>()
    private var anim: ValueAnimator? = null
    private val ev = ArgbEvaluator()

    /** Atenuación nocturna activa: el panel se aclara un poco para no hundirse en el fondo más oscuro. */
    @JvmField var night = false

    /** Dock tintado: color oscuro al 28% de alpha; de noche mezclado 14% con blanco y 34% de alpha. */
    fun dockTint(): Int {
        if (!night) return (dark and 0xFFFFFF) or 0x47000000
        val r = (Color.red(dark) * 0.86f + 255 * 0.14f).toInt()
        val g = (Color.green(dark) * 0.86f + 255 * 0.14f).toInt()
        val b = (Color.blue(dark) * 0.86f + 255 * 0.14f).toInt()
        return Color.argb(0x57, r, g, b)
    }

    fun neutral() = Palette(NEUTRAL_ACCENT, NEUTRAL_LIGHT, NEUTRAL_DARK)

    fun animateTo(p: Palette, ms: Long = 400L, animate: Boolean = true) {
        anim?.cancel()
        val a0 = accent; val l0 = light; val d0 = dark
        if (!animate) { set(p.accent, p.light, p.dark); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener {
                val t = it.animatedFraction
                set(ev.evaluate(t, a0, p.accent) as Int, ev.evaluate(t, l0, p.light) as Int, ev.evaluate(t, d0, p.dark) as Int)
            }
            start()
        }
    }

    private fun set(a: Int, l: Int, d: Int) {
        accent = a; light = l; dark = d
        for (f in listeners) f()
    }

    fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
