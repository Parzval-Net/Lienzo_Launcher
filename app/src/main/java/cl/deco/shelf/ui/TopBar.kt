package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cl.deco.shelf.data.weather.Weather

/** Reloj + fecha (+ feriado) a la izquierda y clima actual a la derecha. Ambos bloques son enfocables. */
class TopBar(ctx: Context, onClock: () -> Unit, onWeather: () -> Unit, onWifi: () -> Unit = {}, onSettings: () -> Unit = {}) : FrameLayout(ctx) {

    private val clock = tv(ctx, 44f, Ui.TEXT, "sans-serif-light").apply {
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
    }
    private val date = tv(ctx, 16f, Ui.TEXT, "sans-serif-medium")
    private val holiday = tv(ctx, 13f, Ui.ACCENT, "sans-serif-medium").apply { visibility = GONE }
    private val icon = ImageView(ctx)
    private val right = LinearLayout(ctx)
    private fun chip(ctx: Context, res: Int) = tv(ctx, 15f, Ui.TEXT, "sans-serif-medium").apply {
        visibility = GONE
        compoundDrawablePadding = ctx.dp(4)
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
        val d = ctx.getDrawable(res)!!.mutate().also { it.setBounds(0, 0, ctx.dp(16), ctx.dp(16)) }
        setCompoundDrawablesRelative(d, null, null, null)
    }
    private val timerChip = chip(ctx, cl.deco.shelf.R.drawable.ic_timer)
    private val alarmChip = chip(ctx, cl.deco.shelf.R.drawable.ic_alarm)
    private val moonChip = chip(ctx, cl.deco.shelf.R.drawable.ic_moon)
    /** Fondo de los avisos: píldora oscura (legible sobre obras claras) y foco con el acento, bien visible. */
    private fun noticeBg(ctx: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply { setColor(Theme.withAlpha(Theme.accent, 0xE0)); cornerRadius = ctx.dpf(18f); setStroke(ctx.dp(2), 0xFFFFFFFF.toInt()) })
        addState(intArrayOf(), GradientDrawable().apply { setColor(0x80000000.toInt()); cornerRadius = ctx.dpf(18f) })
    }
    /** Aviso discreto "Poco espacio": enfocable, abre Almacenamiento. */
    private val lowChip = chip(ctx, cl.deco.shelf.R.drawable.ic_q_storage).apply {
        text = "Poco espacio"
        isFocusable = true; isClickable = true
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        background = noticeBg(ctx)
    }
    /** Aviso "Reiniciar recomendado": memoria de video baja o muchos días encendido. */
    private val healthChip = chip(ctx, cl.deco.shelf.R.drawable.ic_q_storage).apply {
        text = "Reiniciar recomendado"
        isFocusable = true; isClickable = true
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        background = noticeBg(ctx)
    }
    /** El aviso de salud tiene el foco (para que MENU abra el panel de salud y no los ajustes). */
    fun healthFocused() = healthChip.visibility == VISIBLE && healthChip.hasFocus()
    fun setRestartHint(on: Boolean, onClick: () -> Unit) {
        healthChip.visibility = if (on) VISIBLE else GONE
        healthChip.setOnClickListener { onClick() }
    }
    fun setLowSpace(on: Boolean, onClick: () -> Unit) {
        lowChip.visibility = if (on) VISIBLE else GONE
        lowChip.setOnClickListener { onClick() }
    }
    private val status = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(timerChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ctx.dp(10) })
        addView(alarmChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ctx.dp(10) })
        addView(moonChip)
    }
    private val rain = tv(ctx, 16f, Ui.TEXT).apply { alpha = 0.7f; visibility = GONE; setShadowLayer(8f, 0f, 2f, 0x80000000.toInt()); compoundDrawablePadding = ctx.dp(3) }
    private val temp = tv(ctx, 30f, Ui.TEXT, "sans-serif-light").apply { text = "--"; setShadowLayer(8f, 0f, 2f, 0x80000000.toInt()) }

    private fun focusBg(ctx: Context) = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = ctx.dpf(16f) },
        )
        addState(intArrayOf(), ColorDrawable(0))
    }

    init {
        val dateCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), 0, 0, 0)
            for (t in listOf(date, holiday)) {
                t.setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
                addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(1) })
            }
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(18), ctx.dp(6))
            background = focusBg(ctx)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClock() }
            addView(clock)
            addView(dateCol)
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(16) })
        }
        right.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(6))
            background = focusBg(ctx)
            isFocusable = true
            isClickable = true
            setOnClickListener { onWeather() }
            addView(icon, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            addView(temp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(6) })
            addView(rain, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(10) })
        }
        icon.visibility = GONE
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(lowChip)
            addView(healthChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(10) })
        }, LayoutParams(-2, -2, Gravity.CENTER))
        addView(left, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        // Accesos directos junto al clima (como en AT4K y Monet): Wi-Fi y Ajustes
        fun action(res: Int, desc: String, f: () -> Unit) = android.widget.ImageView(ctx).apply {
            setImageResource(res); setColorFilter(Ui.TEXT)
            contentDescription = desc
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            isFocusable = true; isClickable = true
            background = focusBg(ctx)
            setOnClickListener { f() }
        }
        val rightRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(action(cl.deco.shelf.R.drawable.ic_q_wifi, "Wi-Fi", onWifi), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            addView(action(cl.deco.shelf.R.drawable.ic_q_android, "Ajustes", onSettings), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { leftMargin = ctx.dp(4); rightMargin = ctx.dp(10) })
            addView(this@TopBar.right)
        }
        addView(rightRow, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    /** Reloj, clima y acento se actualizan por separado; el clima se oculta con el ajuste "Clima". */
    fun setWeatherEnabled(on: Boolean) { right.visibility = if (on) VISIBLE else GONE }

    fun setAccent(accent: Int, light: Int) {
        holiday.setTextColor(accent)
        rain.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(light)
    }

    /** Indicadores junto al reloj: temporizador activo, próxima alarma (< 12 h) y ventana de apagado nocturno. */
    fun setStatus(timer: String?, alarm: String?, night: Boolean) {
        timerChip.text = timer.orEmpty(); timerChip.visibility = if (timer == null) GONE else VISIBLE
        alarmChip.text = alarm.orEmpty(); alarmChip.visibility = if (alarm == null) GONE else VISIBLE
        moonChip.visibility = if (night) VISIBLE else GONE
    }

    fun setTime(text: String) { if (clock.text != text) clock.text = text }
    fun setDate(text: String) { date.text = text }

    fun setHoliday(name: String?) {
        holiday.text = name.orEmpty()
        holiday.visibility = if (name.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setWeather(w: Weather?) {
        if (w == null) {
            icon.visibility = GONE
            temp.text = "--"
            rain.visibility = GONE
        } else {
            icon.setImageResource(WeatherIcons.iconFor(w.displayCode, w.isDay))
            icon.visibility = VISIBLE
            temp.text = "${Units.temp(w.currentTemp)}°"
            val p = w.rainSoonProb ?: 0
            if (p >= 50) {
                rain.text = "$p%"
                rain.setCompoundDrawablesRelativeWithIntrinsicBounds(cl.deco.shelf.R.drawable.ic_wx_drop, 0, 0, 0)
                rain.visibility = VISIBLE
            } else rain.visibility = GONE
        }
    }
}
