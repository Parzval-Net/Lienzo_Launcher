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
import kotlin.math.roundToInt

/** Reloj + fecha (+ feriado) a la izquierda y clima actual a la derecha. Ambos bloques son enfocables. */
class TopBar(ctx: Context, onToday: () -> Unit) : FrameLayout(ctx) {

    private val clock = tv(ctx, 44f, Ui.TEXT, "sans-serif-light").apply {
        setShadowLayer(8f, 0f, 2f, 0x80000000.toInt())
    }
    private val date = tv(ctx, 16f, Ui.TEXT, "sans-serif-medium")
    private val holiday = tv(ctx, 13f, Ui.ACCENT, "sans-serif-medium").apply { visibility = GONE }
    private val icon = ImageView(ctx)
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
            setOnClickListener { onToday() }
            addView(clock)
            addView(dateCol)
        }
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(6))
            background = focusBg(ctx)
            isFocusable = true
            isClickable = true
            setOnClickListener { onToday() }
            addView(icon, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)))
            addView(temp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(6) })
            addView(rain, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(10) })
        }
        icon.visibility = GONE
        addView(left, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(right, LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
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
            temp.text = "${w.currentTemp.roundToInt()}°"
            val p = w.rainSoonProb ?: 0
            if (p >= 50) {
                rain.text = "$p%"
                rain.setCompoundDrawablesRelativeWithIntrinsicBounds(cl.deco.shelf.R.drawable.ic_wx_drop, 0, 0, 0)
                rain.visibility = VISIBLE
            } else rain.visibility = GONE
        }
    }
}
