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
    const val FOCUS_SCALE = 1.06f
    val DECEL: android.view.animation.Interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
}

/**
 * Estilo común de paneles (estilo tvOS, el mismo del desplegable de Entradas): tarjeta de vidrio ahumado con borde claro
 * y foco blanco con texto oscuro. Lo usan Entradas, menús, Ajustes, Actualizaciones, Clima y Reloj para que todo se vea igual.
 */
object Glass {
    const val CARD = 0xEB1C212B.toInt()
    const val CARD_SOLID = 0xF51A1F29.toInt()
    const val STROKE = 0x42FFFFFF
    const val FOCUS = 0xF5FFFFFF.toInt()
    const val FOCUS_TEXT = 0xFF161A22.toInt()
    const val FOCUS_TEXT2 = 0xFF4B5262.toInt()
    const val GROUP = 0x14FFFFFF
    const val GROUP_STROKE = 0x1CFFFFFF
    const val DIVIDER = 0x16FFFFFF
    const val INFO_FOCUS = 0x26FFFFFF
    const val INFO_FOCUS_STROKE = 0x48FFFFFF
    const val PILL_IDLE = 0x781A1F29
    const val SELECTED = 0x38FFFFFF
    const val IDLE = 0x1FFFFFFF
    /** Fondo detrás de los paneles: oscurece la obra sin taparla. */
    const val SCRIM = 0xCC0B0D14.toInt()

    fun card(ctx: Context, radius: Float = 22f) = android.graphics.drawable.GradientDrawable().apply {
        setColor(CARD); cornerRadius = ctx.dpf(radius); setStroke(ctx.dp(1), STROKE)
    }

    /** Sub-tarjeta para agrupar filas de una misma sección con borde y fondo sutil. */
    fun groupCard(ctx: Context, radius: Float = 14f) = android.graphics.drawable.GradientDrawable().apply {
        setColor(GROUP); cornerRadius = ctx.dpf(radius); setStroke(ctx.dp(1), GROUP_STROKE)
    }

    /** Fondo enfocable: blanco con foco, vidrio claro seleccionado/activado y [idle] en reposo (0 = transparente). */
    fun bg(ctx: Context, radius: Float = 14f, idle: Int = IDLE) = android.graphics.drawable.StateListDrawable().apply {
        fun round(c: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = ctx.dpf(radius) }
        addState(intArrayOf(android.R.attr.state_focused), round(FOCUS))
        addState(intArrayOf(android.R.attr.state_selected), round(SELECTED))
        addState(intArrayOf(android.R.attr.state_activated), round(SELECTED))
        addState(intArrayOf(), if (idle == 0) android.graphics.drawable.ColorDrawable(0) else round(idle))
    }

    /** Fondo de foco suave para filas informativas (no clickables): mantiene el texto claro 100% legible. */
    fun infoBg(ctx: Context, radius: Float = 12f) = android.graphics.drawable.StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            android.graphics.drawable.GradientDrawable().apply {
                setColor(INFO_FOCUS)
                setStroke(ctx.dp(1), INFO_FOCUS_STROKE)
                cornerRadius = ctx.dpf(radius)
            },
        )
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
    }

    /** Color de texto que pasa a oscuro cuando la fila (o la propia vista) tiene foco. */
    fun fg(normal: Int = Ui.TEXT, focused: Int = FOCUS_TEXT) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(focused, normal))

    /** Texto dentro de una fila enfocable: hereda el foco del padre y se oscurece sobre el blanco. */
    fun inRow(t: TextView, normal: Int = Ui.TEXT): TextView = t.apply {
        isDuplicateParentStateEnabled = true
        setTextColor(fg(normal, if (normal == Ui.TEXT2) FOCUS_TEXT2 else FOCUS_TEXT))
    }
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

/** Unidades de temperatura elegidas en ajustes. */
object Units {
    @JvmField var fahrenheit = false
    fun temp(c: Double): Int = Math.round(if (fahrenheit) c * 9.0 / 5.0 + 32.0 else c).toInt()
}

/** Textos de fecha en español de Chile (por defecto) o inglés, según ajustes; no depende del locale del equipo. */
object Es {
    @JvmField var english = false
    @JvmField var sundayFirst = false

    private val diasEs = arrayOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val diasEn = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val mesesEs = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val mesesEn = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    val dias get() = if (english) diasEn else diasEs
    val meses get() = if (english) mesesEn else mesesEs
    val diasCorto get() = dias.map { it.take(3) }.toTypedArray()

    /** Letras de cabecera del calendario según primer día de la semana. */
    fun headers(): List<String> {
        val l = if (english) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("L", "M", "M", "J", "V", "S", "D")
        return if (sundayFirst) listOf(l[6]) + l.take(6) else l
    }

    fun fecha(d: LocalDate) = if (english) "${dias[d.dayOfWeek.value - 1]}, ${meses[d.monthValue - 1]} ${d.dayOfMonth}"
    else "${dias[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }}, ${d.dayOfMonth} de ${meses[d.monthValue - 1]}"
    fun corta(d: LocalDate) = if (english) "${diasCorto[d.dayOfWeek.value - 1]} ${meses[d.monthValue - 1].take(3)} ${d.dayOfMonth}"
    else "${diasCorto[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${meses[d.monthValue - 1].take(3)}"
    fun mes(m: Int, y: Int) = "${meses[m - 1].replaceFirstChar { it.uppercase() }} $y"
    fun hoy() = if (english) "Today" else "Hoy"

    /** Hora según formato 24 h / 12 h. */
    fun hora(h: Int, m: Int, h24: Boolean): String =
        if (h24) "%02d:%02d".format(h, m) else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}
