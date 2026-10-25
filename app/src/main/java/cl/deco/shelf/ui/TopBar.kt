package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cl.deco.shelf.data.weather.Weather
import cl.deco.shelf.data.weather.WeatherStatus
import cl.deco.shelf.data.weather.WeatherView

/**
 * Barra superior con saludo dinámico, reloj grande, fecha completa en español,
 * indicadores de estado, píldoras estilo tvOS (Entradas HDMI, Wi-Fi, Ajustes) y bloque de clima.
 */
class TopBar(
    private val ctx: Context,
    onClock: () -> Unit,
    onWeather: () -> Unit,
    onInputs: () -> Unit = {},
    onWifi: () -> Unit = {},
    onSettings: () -> Unit = {},
) : FrameLayout(ctx) {

    private val greeting = tv(ctx, 15f, Ui.TEXT, "sans-serif-medium").apply {
        alpha = 0.85f
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
    }

    private val clock = tv(ctx, 44f, Ui.TEXT, "sans-serif-light").apply {
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
    }
    private val date = tv(ctx, 16f, Ui.TEXT, "sans-serif-medium")
    private val holiday = tv(ctx, 13f, Ui.ACCENT, "sans-serif-medium").apply { visibility = GONE }
    private val icon = ImageView(ctx)
    private val right = LinearLayout(ctx)

    private fun chip(ctx: Context, res: Int) = tv(ctx, 15f, Ui.TEXT, "sans-serif-medium").apply {
        visibility = GONE
        compoundDrawablePadding = context.dp(4)
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
        val d = ctx.getDrawable(res)!!.mutate().also { it.setBounds(0, 0, context.dp(16), context.dp(16)) }
        setCompoundDrawablesRelative(d, null, null, null)
    }

    private val timerChip = chip(ctx, cl.deco.shelf.R.drawable.ic_timer)
    private val alarmChip = chip(ctx, cl.deco.shelf.R.drawable.ic_alarm)
    private val moonChip = chip(ctx, cl.deco.shelf.R.drawable.ic_moon)

    /** Fondos con foco de píldoras y avisos: se retiñen con el acento vigente en [setAccent]. */
    private val accentBgs = ArrayList<GradientDrawable>()

    private fun accentFocus(): GradientDrawable = GradientDrawable().apply {
        setColor(Theme.withAlpha(Theme.accent, 0xD8))
        cornerRadius = context.dpf(18f)
        setStroke(context.dp(2), 0xFFFFFFFF.toInt())
    }.also { accentBgs.add(it) }

    /** Fondo de las píldoras y avisos estilo tvOS. */
    private fun noticeBg(ctx: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), accentFocus())
        addState(
            intArrayOf(),
            GradientDrawable().apply {
                setColor(0x80000000.toInt())
                cornerRadius = context.dpf(18f)
            },
        )
    }

    private val lowChip = chip(ctx, cl.deco.shelf.R.drawable.ic_q_storage).apply {
        text = "Poco espacio"
        isFocusable = true; isClickable = true
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        background = noticeBg(ctx)
    }

    private val healthChip = chip(ctx, cl.deco.shelf.R.drawable.ic_q_storage).apply {
        text = "Reiniciar recomendado"
        isFocusable = true; isClickable = true
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        background = noticeBg(ctx)
    }

    fun healthFocused() = healthChip.visibility == VISIBLE && healthChip.hasFocus()
    fun setRestartHint(on: Boolean, onClick: () -> Unit) {
        healthChip.visibility = if (on) VISIBLE else GONE
        healthChip.setOnClickListener { onClick() }
        renderUpdate()
    }
    fun setLowSpace(on: Boolean, onClick: () -> Unit) {
        lowChip.visibility = if (on) VISIBLE else GONE
        lowChip.setOnClickListener { onClick() }
    }

    private val updateChip = chip(ctx, cl.deco.shelf.R.drawable.ic_q_android).apply {
        isFocusable = true; isClickable = true
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        background = noticeBg(ctx)
    }
    private var updateText: String? = null
    private fun renderUpdate() {
        updateChip.visibility = if (updateText != null && healthChip.visibility != VISIBLE) VISIBLE else GONE
    }
    fun setUpdateHint(text: String?, onClick: () -> Unit) {
        updateText = text
        if (text != null) updateChip.text = text
        updateChip.setOnClickListener { onClick() }
        renderUpdate()
    }

    private val status = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(timerChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = context.dp(10) })
        addView(alarmChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = context.dp(10) })
        addView(moonChip)
    }
    private val rain = tv(ctx, 16f, Ui.TEXT).apply { alpha = 0.7f; visibility = GONE; setShadowLayer(8f, 0f, 2f, 0x80000000.toInt()); compoundDrawablePadding = context.dp(3) }
    private val temp = tv(ctx, 30f, Ui.TEXT, "sans-serif-light").apply { text = "--"; setShadowLayer(8f, 0f, 2f, 0x80000000.toInt()) }
    private val offlineText = tv(ctx, 16f, Ui.TEXT, "sans-serif-medium").apply { visibility = GONE; setShadowLayer(8f, 0f, 2f, 0x80000000.toInt()) }
    private var lastWeather: Weather? = null
    private var online = true

    private var wifiLabel: TextView? = null
    private var inputsPill: View? = null
    private var wifiPill: View? = null
    private val artChip = tv(ctx, 13f, Ui.TEXT2, "sans-serif-medium").apply {
        visibility = GONE
        setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
        background = GradientDrawable().apply { setColor(0x59000000); cornerRadius = context.dpf(14f) }
    }

    private fun focusBg(ctx: Context) = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = context.dpf(16f) },
        )
        addState(intArrayOf(), ColorDrawable(0))
    }

    /** Crea una píldora traslúcida con icono y texto para la barra superior estilo Apple TV / tvOS. */
    private fun pill(iconRes: Int, text: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(context.dp(12), context.dp(6), context.dp(14), context.dp(6))
        contentDescription = text
        // Estilo tvOS: en reposo vidrio translúcido con borde claro; con foco, blanco con texto e ícono oscuros
        val on = GradientDrawable().apply { setColor(0xF2FFFFFF.toInt()); cornerRadius = context.dpf(20f) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_activated), on) // Entradas con su desplegable abierto
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(0x664A5466)
                cornerRadius = context.dpf(20f)
                setStroke(context.dp(1), 0x59FFFFFF)
            })
        }
        val fg = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_activated), intArrayOf()),
            intArrayOf(0xFF2A2F3A.toInt(), 0xFF2A2F3A.toInt(), Ui.TEXT))
        val img = ImageView(ctx).apply {
            setImageResource(iconRes)
            imageTintList = fg
            isDuplicateParentStateEnabled = true
        }
        addView(img, LinearLayout.LayoutParams(context.dp(18), context.dp(18)))
        val lbl = tv(ctx, 14f, Ui.TEXT, "sans-serif-medium").apply {
            this.text = text
            tag = "pill_label"
            setTextColor(fg)
            isDuplicateParentStateEnabled = true
        }
        addView(lbl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(6) })
        setOnClickListener { onClick() }
    }

    init {
        val dateCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), 0, 0, 0)
            addView(greeting, LinearLayout.LayoutParams(-2, -2))
            addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(2) })
            addView(holiday, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(1) })
        }

        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(18), context.dp(8))
            background = focusBg(ctx)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClock() }
            addView(clock)
            addView(dateCol)
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(16) })
        }

        right.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
            background = focusBg(ctx)
            isFocusable = true
            isClickable = true
            setOnClickListener { onWeather() }
            addView(icon, LinearLayout.LayoutParams(context.dp(36), context.dp(36)))
            addView(temp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(6) })
            addView(offlineText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(6) })
            addView(rain, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(8) })
        }
        icon.visibility = GONE

        // Centro: avisos de salud, almacenamiento, actualizaciones y descarga
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(artChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = context.dp(10) })
            addView(lowChip)
            addView(healthChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(10) })
            addView(updateChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(10) })
        }, LayoutParams(-2, -2, Gravity.CENTER))

        // Izquierda: saludo, reloj y fecha
        addView(left, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))

        // Derecha: píldoras superiores (Entradas, Wi-Fi, Ajustes) y bloque de clima
        val rightRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            val inPill = pill(cl.deco.shelf.R.drawable.ic_in_hdmi, "Entradas", onInputs).also { inputsPill = it }
            addView(inPill)
            val wifiPill = pill(cl.deco.shelf.R.drawable.ic_q_wifi, "Wi-Fi", onWifi).also {
                wifiLabel = it.findViewWithTag("pill_label")
                this@TopBar.wifiPill = it
            }
            addView(wifiPill, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(8) })
            addView(pill(cl.deco.shelf.R.drawable.ic_q_settings, "Ajustes", onSettings), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(8); rightMargin = context.dp(10) })
            addView(this@TopBar.right)
        }
        addView(rightRow, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    /** Primera píldora visible (Entradas, o Wi-Fi en equipos sin entradas de video como un deco). */
    fun focusInputs(): Boolean = (inputsPill?.takeIf { it.visibility == VISIBLE } ?: wifiPill)?.requestFocus() ?: false

    /** Decos y equipos sin HDMI/AV/sintonizador: la píldora Entradas no tiene sentido y se oculta. */
    fun setInputsAvailable(on: Boolean) { inputsPill?.visibility = if (on) VISIBLE else GONE }

    /** Píldora Entradas: ancla del desplegable. */
    fun inputsAnchor(): View? = inputsPill

    /** Actualiza el saludo según la hora del día. */

    fun setGreeting(hour: Int) {
        val text = when (hour) {
            in 6..11 -> "Buenos días"
            in 12..19 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        if (greeting.text != text) greeting.text = text
    }

    fun setWeatherEnabled(on: Boolean) { right.visibility = if (on) VISIBLE else GONE }

    fun setAccent(accent: Int, light: Int) {
        for (b in accentBgs) b.setColor(Theme.withAlpha(accent, 0xD8))
        holiday.setTextColor(accent)
        rain.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(light)
    }

    fun setStatus(timer: String?, alarm: String?, night: Boolean) {
        timerChip.text = timer.orEmpty(); timerChip.visibility = if (timer == null) GONE else VISIBLE
        alarmChip.text = alarm.orEmpty(); alarmChip.visibility = if (alarm == null) GONE else VISIBLE
        moonChip.visibility = if (night) VISIBLE else GONE
    }

    fun setWifiSsid(name: String?) {
        val label = if (!name.isNullOrBlank()) name else "Wi-Fi"
        if (wifiLabel?.text != label) wifiLabel?.text = label
    }

    fun setTime(text: String) { if (clock.text != text) clock.text = text }
    fun setDate(text: String) { date.text = text }

    fun setHoliday(name: String?) {
        holiday.text = name.orEmpty()
        holiday.visibility = if (name.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setWeather(w: Weather?) { lastWeather = w; renderWeather() }

    fun setOnline(on: Boolean) { if (online != on) { online = on; renderWeather() } }

    fun setArtProgress(text: String?) {
        if (text == null) { artChip.visibility = GONE; return }
        if (artChip.text != text) artChip.text = text
        artChip.visibility = VISIBLE
    }

    private fun renderWeather() {
        val w = lastWeather
        when (WeatherStatus.of(w, online, System.currentTimeMillis())) {
            WeatherView.LOADING -> {
                icon.visibility = GONE; offlineText.visibility = GONE; rain.visibility = GONE
                temp.visibility = VISIBLE; temp.text = "--"
            }
            WeatherView.OFFLINE -> {
                icon.setImageResource(cl.deco.shelf.R.drawable.ic_wx_offline); icon.visibility = VISIBLE
                temp.visibility = GONE; rain.visibility = GONE
                offlineText.text = if (Es.english) "Offline" else "Sin conexión"; offlineText.visibility = VISIBLE
            }
            WeatherView.SHOW -> {
                w!!
                offlineText.visibility = GONE; temp.visibility = VISIBLE
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
}