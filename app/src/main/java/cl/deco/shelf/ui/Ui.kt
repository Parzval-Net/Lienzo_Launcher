package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Última dirección de D-pad (para el parallax del foco). */
object Nav {
    @JvmField var dx = 0
    @JvmField var dy = 0
}

object Ui {
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFFB8B8C0.toInt()
    const val ACCENT = 0xFFFF7A1A.toInt()
    const val FOCUS_MS = 150L
    const val FOCUS_SCALE = 1.08f
    val DECEL: android.view.animation.Interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun tv(ctx: Context, size: Float, color: Int = Ui.TEXT, face: String = "sans-serif"): TextView = TextView(ctx).apply {
    textSize = size
    setTextColor(color)
    typeface = Typeface.create(face, Typeface.NORMAL)
    includeFontPadding = false
}

/** Executor de un hilo que libera el hilo al estar ocioso (nada vivo en reposo). */
fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
    allowCoreThreadTimeOut(true)
}

/** Textos en español de Chile (no depende del locale del equipo). */
object Es {
    val dias = arrayOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    val diasCorto = arrayOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    val meses = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )

    fun fecha(d: LocalDate) = "${dias[d.dayOfWeek.value - 1]} ${d.dayOfMonth} de ${meses[d.monthValue - 1]}"
    fun corta(d: LocalDate) = "${diasCorto[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${meses[d.monthValue - 1].take(3)}"
    fun mes(m: Int, y: Int) = "${meses[m - 1].replaceFirstChar { it.uppercase() }} $y"
}
