package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cl.deco.shelf.data.holidays.Holiday
import cl.deco.shelf.data.weather.Weather
import java.time.LocalDate
import java.time.YearMonth

/** Overlay "Hoy": calendario mensual (L a D), próximos feriados y pronóstico de 7 días. */
class TodayPanel(ctx: Context) : FrameLayout(ctx) {

    var onNeedYear: ((Int) -> Unit)? = null

    private val holidays = HashMap<Int, List<Holiday>>()
    private var ym: YearMonth = YearMonth.now()
    private var today: LocalDate = LocalDate.now()
    private var weather: Weather? = null

    private var city = ""
    private val nextTitle = tv(ctx, 15f, Ui.TEXT, "sans-serif-medium")
    private val weekdays = ArrayList<TextView>()
    private val fcTitle = tv(ctx, 20f, Ui.TEXT, "sans-serif-medium")
    private val title = tv(ctx, 20f, Ui.TEXT, "sans-serif-medium")
    private val cells = Array(42) { tv(ctx, 14f, Ui.TEXT).apply { gravity = Gravity.CENTER } }
    private val nextBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val fcBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        visibility = GONE
        setBackgroundColor(0xF50B0B10.toInt())
        isClickable = true

        fun card(w: Int) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = ctx.dpf(20f) }
            layoutParams = LinearLayout.LayoutParams(ctx.dp(w), -2)
        }

        val left = card(430)
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(ctx, 24f, Ui.TEXT2).apply { text = "‹" }, LinearLayout.LayoutParams(ctx.dp(32), -2))
        head.addView(title.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv(ctx, 24f, Ui.TEXT2).apply { text = "›"; gravity = Gravity.END }, LinearLayout.LayoutParams(ctx.dp(32), -2))
        left.addView(head, LinearLayout.LayoutParams(-1, -2))

        val wd = LinearLayout(ctx)
        for (i in 0 until 7) {
            wd.addView(tv(ctx, 12f, Ui.TEXT2, "sans-serif-medium").apply { gravity = Gravity.CENTER; weekdays.add(this) }, LinearLayout.LayoutParams(0, ctx.dp(24), 1f))
        }
        left.addView(wd, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        for (r in 0 until 6) {
            val row = LinearLayout(ctx)
            for (c in 0 until 7) row.addView(cells[r * 7 + c], LinearLayout.LayoutParams(0, ctx.dp(30), 1f))
            left.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        left.addView(nextTitle,
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(12); bottomMargin = ctx.dp(4) })
        left.addView(nextBox, LinearLayout.LayoutParams(-1, -2))

        val right = card(360)
        right.addView(fcTitle)
        right.addView(fcBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })

        val row = LinearLayout(ctx).apply { gravity = Gravity.TOP }
        row.addView(left)
        row.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(24), 1))
        row.addView(right)
        addView(row, LayoutParams(-2, -2, Gravity.CENTER))
        Theme.listeners.add { if (visibility == VISIBLE) { bindMonth(); bindForecast() } }
    }

    fun setCity(name: String) { city = name; bindTitles() }

    /** Los feriados cambian (país o región): limpia y vuelve a pedir. */
    fun clearHolidays() {
        holidays.clear()
        if (visibility == VISIBLE) { bindMonth(); bindNext() }
    }

    /** Idioma, primer día o unidades cambiaron. */
    fun refresh() { if (visibility == VISIBLE) { bindMonth(); bindNext(); bindForecast() } }

    private fun bindTitles() {
        fcTitle.text = if (Es.english) "7-day forecast" else "Pronóstico 7 días"
        if (city.isNotEmpty()) fcTitle.text = "${fcTitle.text} · $city"
        nextTitle.text = if (Es.english) "Upcoming holidays" else "Próximos feriados"
        val h = Es.headers()
        for (i in 0 until 7) weekdays[i].text = h[i]
    }

    fun open(w: Weather?, now: LocalDate) {
        bindTitles()
        weather = w
        today = now
        ym = YearMonth.from(now)
        bindMonth()
        bindForecast()
        bindNext()
        needYear(now.year)
        needYear(now.year + 1)
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
    }

    fun close() {
        animate().cancel()
        visibility = GONE
    }

    fun setWeather(w: Weather?) {
        weather = w
        if (visibility == VISIBLE) bindForecast()
    }

    fun setHolidays(year: Int, list: List<Holiday>) {
        holidays[year] = list
        if (visibility == VISIBLE) { bindMonth(); bindNext() }
    }

    fun shift(delta: Int) {
        ym = ym.plusMonths(delta.toLong())
        bindMonth()
    }

    private fun needYear(y: Int) { if (!holidays.containsKey(y)) onNeedYear?.invoke(y) }

    private fun holidayOn(d: LocalDate): Holiday? = holidays[d.year]?.firstOrNull { it.date == d }

    private fun bindMonth() {
        needYear(ym.year)
        title.text = Es.mes(ym.monthValue, ym.year)
        val first = ym.atDay(1)
        val offset = if (Es.sundayFirst) first.dayOfWeek.value % 7 else first.dayOfWeek.value - 1
        for (i in 0 until 42) {
            val d = first.plusDays((i - offset).toLong())
            val inMonth = d.month == ym.month
            val c = cells[i]
            c.text = d.dayOfMonth.toString()
            val isToday = d == today
            val hol = inMonth && holidayOn(d) != null
            c.setTextColor(
                when {
                    isToday -> 0xFF111111.toInt()
                    inMonth -> Ui.TEXT
                    else -> 0x44FFFFFF
                },
            )
            c.background = if (isToday || hol) android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isToday) 0xFFFFFFFF.toInt() else Theme.accent)
                if (isToday && hol) setStroke(context.dp(2), Theme.accent)
            }, context.dp(12), 0, context.dp(12), 0) else null
            c.setPadding(0, 0, 0, 0)
        }
    }

    private fun bindNext() {
        nextBox.removeAllViews()
        val ctx = context
        val all = (holidays[today.year].orEmpty() + holidays[today.year + 1].orEmpty())
            .filter { !it.date.isBefore(today) }.sortedBy { it.date }.take(4)
        if (all.isEmpty()) {
            nextBox.addView(tv(ctx, 13f, Ui.TEXT2).apply {
                text = if (holidays.containsKey(today.year)) (if (Es.english) "No holiday data" else "Sin datos de feriados") else (if (Es.english) "Loading..." else "Cargando...")
            })
            return
        }
        for (h in all) {
            val r = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(tv(ctx, 13f, Ui.TEXT2).apply { text = Es.corta(h.date) }, LinearLayout.LayoutParams(ctx.dp(84), -2))
            r.addView(tv(ctx, 13f, Ui.TEXT).apply { text = h.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (h.irrenunciable) r.addView(tv(ctx, 11f, Theme.accent, "sans-serif-medium").apply { text = if (Es.english) "no waiver" else "irrenunciable" },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(6) })
            nextBox.addView(r, LinearLayout.LayoutParams(-1, ctx.dp(24)))
        }
    }

    private fun bindForecast() {
        fcBox.removeAllViews()
        val ctx = context
        val days = weather?.days?.take(7).orEmpty()
        if (days.isEmpty()) {
            fcBox.addView(tv(ctx, 13f, Ui.TEXT2).apply { text = if (Es.english) "No weather data" else "Sin datos de clima" })
            return
        }
        for (d in days) {
            val r = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = if (d.date == today) Es.hoy() else Es.diasCorto[d.date.dayOfWeek.value - 1] + " " + d.date.dayOfMonth
            r.addView(tv(ctx, 14f, Ui.TEXT).apply { text = name }, LinearLayout.LayoutParams(ctx.dp(78), -2))
            r.addView(ImageView(ctx).apply { setImageResource(WeatherIcons.iconFor(d.code)) }, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
            r.addView(tv(ctx, 14f, Ui.TEXT).apply {
                text = "${Units.temp(d.tMin)}° / ${Units.temp(d.tMax)}°"
                setPadding(ctx.dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(tv(ctx, 13f, Theme.light).apply {
                text = d.precipProb?.let { "$it%" } ?: ""
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(ctx.dp(48), -2))
            fcBox.addView(r, LinearLayout.LayoutParams(-1, ctx.dp(42)))
        }
    }
}
