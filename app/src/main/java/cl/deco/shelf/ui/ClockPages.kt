package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cl.deco.shelf.clock.AlarmItem
import cl.deco.shelf.clock.ClockScheduler
import cl.deco.shelf.clock.ClockStore
import cl.deco.shelf.clock.TimerItem

/** Utilidades de las páginas Temporizador y Alarma (D-pad: chips enfocables y selectores con ARRIBA/ABAJO). */
internal object PageUi {
    /** Estilo común (ver [Glass]): blanco con foco, vidrio claro seleccionado. Quien lo use con texto debe aplicar Glass.fg. */
    fun focusBg(ctx: Context, radius: Float = 14f) = Glass.bg(ctx, radius)

    fun chip(ctx: Context, text: String, w: Int = 0, onClick: () -> Unit): TextView = tv(ctx, 15f, Ui.TEXT, "sans-serif-medium").apply {
        this.text = text; gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        background = focusBg(ctx, 20f)
        setTextColor(Glass.fg())
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(if (w > 0) ctx.dp(w) else -2, ctx.dp(40)).apply { rightMargin = ctx.dp(8) }
    }

    /** Selector numérico: ARRIBA/ABAJO cambian el valor; IZQUIERDA/DERECHA mueven el foco. */
    class Spinner(ctx: Context, private val max: Int, private val unit: String, start: Int = 0) : TextView(ctx) {
        var value = start
            set(v) { field = ((v % (max + 1)) + (max + 1)) % (max + 1); text = "%02d %s".format(field, unit) }
        init {
            textSize = 20f; setTextColor(Glass.fg()); gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            background = focusBg(ctx)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(48)).apply { rightMargin = ctx.dp(8) }
            value = start
            setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP -> { value += 1; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { value -= 1; true }
                    else -> false
                }
            }
        }
    }

    fun title(ctx: Context, t: String) = tv(ctx, 14f, Theme.light, "sans-serif-medium").apply {
        text = t.uppercase(); letterSpacing = 0.08f
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(6) }
    }

    fun scroll(ctx: Context, body: LinearLayout) = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFocusable = false
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }

    fun row(ctx: Context) = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
}

/** Página Temporizador: presets, personalizado (horas y minutos), y hasta 3 temporizadores activos (pausar/cancelar). */
class TimerPage(private val ctx: Context, private val onChanged: () -> Unit) {
    private val store = ClockStore(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val hours = PageUi.Spinner(ctx, 23, "h")
    private val mins = PageUi.Spinner(ctx, 59, "min", 10)
    private var shown = false
    private val rowTexts = HashMap<Int, TextView>()
    private val ticker = object : Runnable {
        // Solo actualiza los textos (reconstruir las filas cada segundo haría perder el foco)
        override fun run() { if (shown) { tickTexts(); main.postDelayed(this, 1000) } }
    }

    val view: View

    init {
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(PageUi.title(ctx, "Inicio rápido"))
        val presets = PageUi.row(ctx)
        for (m in listOf(5, 10, 15, 30, 60)) presets.addView(PageUi.chip(ctx, "$m min", 84) { start(m * 60_000L) })
        body.addView(presets)
        body.addView(PageUi.title(ctx, "Personalizado"))
        val custom = PageUi.row(ctx)
        custom.addView(hours); custom.addView(mins)
        custom.addView(PageUi.chip(ctx, "Iniciar", 110) {
            val ms = (hours.value * 60L + mins.value) * 60_000L
            if (ms > 0) start(ms)
        })
        body.addView(custom)
        body.addView(tv(ctx, 12f, Ui.TEXT2).apply { text = "Con el foco en horas o minutos: ARRIBA y ABAJO cambian el valor" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(4) })
        body.addView(PageUi.title(ctx, "Activos (máx. ${ClockStore.MAX_TIMERS})"))
        body.addView(list)
        view = PageUi.scroll(ctx, body)
    }

    fun show() { shown = true; bindList(); main.removeCallbacks(ticker); main.postDelayed(ticker, 1000) }
    fun hide() { shown = false; main.removeCallbacks(ticker) }

    private fun start(ms: Long) {
        val t = store.startTimer(ms)
        if (t == null) { Toast.makeText(ctx, "Máximo ${ClockStore.MAX_TIMERS} temporizadores", Toast.LENGTH_SHORT).show(); return }
        ClockScheduler.scheduleTimer(ctx, t)
        bindList(); onChanged()
    }

    private fun fmt(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun label(t: TimerItem, now: Long) = "${if (t.running) "▶" else "❚❚"}  ${fmt(t.left(now))}  ·  ${t.totalMs / 60_000} min"

    private fun tickTexts() {
        val now = System.currentTimeMillis()
        val all = store.timers()
        if (all.any { it.running && it.endAt <= now } || all.map { it.id }.toSet() != rowTexts.keys) { bindList(); return }
        for (t in all) rowTexts[t.id]?.text = label(t, now)
    }

    private fun bindList() {
        val now = System.currentTimeMillis()
        val timers = store.timers().filter { !it.running || it.endAt > now }
        val focusedId = (list.findFocus()?.tag as? Int)
        list.removeAllViews(); rowTexts.clear()
        if (timers.isEmpty()) { list.addView(tv(ctx, 14f, Ui.TEXT2).apply { text = "Sin temporizadores activos" }); return }
        for (t in timers) {
            val row = PageUi.row(ctx)
            val main = PageUi.chip(ctx, label(t, now), 0) { toggle(t) }
            rowTexts[t.id] = main
            main.layoutParams = LinearLayout.LayoutParams(ctx.dp(300), ctx.dp(40)).apply { rightMargin = ctx.dp(8) }
            main.gravity = Gravity.CENTER_VERTICAL; main.tag = t.id
            val cancel = PageUi.chip(ctx, "Cancelar", 110) { cancel(t) }
            row.addView(main); row.addView(cancel)
            list.addView(row, LinearLayout.LayoutParams(-2, ctx.dp(46)))
            if (focusedId == t.id) main.requestFocus()
        }
    }

    private fun toggle(t: TimerItem) {
        val now = System.currentTimeMillis()
        val upd = if (t.running) { ClockScheduler.cancelTimer(ctx, t.id); t.copy(endAt = 0, remainingMs = t.left(now)) }
        else t.copy(endAt = now + t.remainingMs).also { ClockScheduler.scheduleTimer(ctx, it) }
        store.saveTimers(store.timers().map { if (it.id == t.id) upd else it })
        bindList(); onChanged()
    }

    private fun cancel(t: TimerItem) {
        ClockScheduler.cancelTimer(ctx, t.id)
        store.saveTimers(store.timers().filter { it.id != t.id })
        bindList(); onChanged()
        if (view.findFocus() == null) view.requestFocus() // la fila enfocada desapareció: foco a la página
    }
}

/** Página Alarma: hora, repetición por día (o única), etiqueta opcional y lista con activar/desactivar y borrar. */
class AlarmPage(private val ctx: Context, private val onChanged: () -> Unit) {
    private val store = ClockStore(ctx)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val hh = PageUi.Spinner(ctx, 23, "h", 7)
    private val mm = PageUi.Spinner(ctx, 59, "min", 0)
    private val days = BooleanArray(7)
    private val labelEt = EditText(ctx)
    private val dayChips = ArrayList<TextView>()

    val view: View

    init {
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(PageUi.title(ctx, "Nueva alarma"))
        val r1 = PageUi.row(ctx)
        r1.addView(hh); r1.addView(mm)
        r1.addView(PageUi.chip(ctx, "Agregar", 110) { add() })
        body.addView(r1)
        val r2 = PageUi.row(ctx)
        r2.addView(tv(ctx, 14f, Ui.TEXT2).apply { text = "Repetir:  " })
        val names = listOf("L", "M", "M", "J", "V", "S", "D")
        for (i in 0 until 7) {
            val c = PageUi.chip(ctx, names[i], 44) { days[i] = !days[i]; dayChips[i].isSelected = days[i] }
            c.setPadding(0, 0, 0, 0)
            dayChips.add(c); r2.addView(c)
        }
        r2.addView(tv(ctx, 12f, Ui.TEXT2).apply { text = "  sin días = única" })
        body.addView(r2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        labelEt.apply {
            hint = "Etiqueta (opcional)"; textSize = 16f
            setTextColor(Glass.fg())
            setHintTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0x992A2F3A.toInt(), 0x88FFFFFF.toInt())))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true); setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            background = PageUi.focusBg(ctx)
            setOnClickListener { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, 0) }
        }
        body.addView(labelEt, LinearLayout.LayoutParams(ctx.dp(400), ctx.dp(44)).apply { topMargin = ctx.dp(8) })
        body.addView(PageUi.title(ctx, "Alarmas"))
        body.addView(list)
        view = PageUi.scroll(ctx, body)
    }

    fun show() = bindList()

    private fun daysText(m: Int): String = if (m == 0) "Una vez" else listOf("L", "M", "M", "J", "V", "S", "D").filterIndexed { i, _ -> m and (1 shl i) != 0 }.joinToString(" ")

    private fun add() {
        var mask = 0
        for (i in 0 until 7) if (days[i]) mask = mask or (1 shl i)
        val a = AlarmItem(store.nextId(), hh.value, mm.value, mask, labelEt.text.toString().trim(), true)
        store.saveAlarms(store.alarms() + a)
        ClockScheduler.scheduleAlarm(ctx, a)
        labelEt.setText("")
        bindList(); onChanged()
    }

    private fun bindList() {
        val alarms = store.alarms().sortedWith(compareBy({ it.hour }, { it.minute }))
        val focusedId = (list.findFocus()?.tag as? Int)
        list.removeAllViews()
        if (alarms.isEmpty()) { list.addView(tv(ctx, 14f, Ui.TEXT2).apply { text = "Sin alarmas" }); return }
        for (a in alarms) {
            val row = PageUi.row(ctx)
            val txt = "%02d:%02d   %s%s   %s".format(a.hour, a.minute, daysText(a.days), if (a.label.isNotBlank()) "  ·  ${a.label}" else "", if (a.enabled) "ACTIVA" else "apagada")
            val main = PageUi.chip(ctx, txt, 0) { toggle(a) }
            main.layoutParams = LinearLayout.LayoutParams(ctx.dp(420), ctx.dp(40)).apply { rightMargin = ctx.dp(8) }
            main.gravity = Gravity.CENTER_VERTICAL; main.alpha = if (a.enabled) 1f else 0.6f; main.tag = a.id
            row.addView(main); row.addView(PageUi.chip(ctx, "Borrar", 90) { delete(a) })
            list.addView(row, LinearLayout.LayoutParams(-2, ctx.dp(46)))
            if (focusedId == a.id) main.requestFocus()
        }
    }

    private fun toggle(a: AlarmItem) {
        val n = a.copy(enabled = !a.enabled)
        store.saveAlarms(store.alarms().map { if (it.id == a.id) n else it })
        ClockScheduler.scheduleAlarm(ctx, n)
        bindList(); onChanged()
    }

    private fun delete(a: AlarmItem) {
        ClockScheduler.cancelAlarm(ctx, a.id)
        store.saveAlarms(store.alarms().filter { it.id != a.id })
        bindList(); onChanged()
        if (view.findFocus() == null) view.requestFocus()
    }
}
