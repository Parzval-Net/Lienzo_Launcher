package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Filas de una página del panel de ajustes. */
sealed class Row {
    class Section(val title: String) : Row()
    /** Opciones cíclicas: IZQUIERDA/DERECHA o OK cambian el valor. */
    class Choice(val label: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit) : Row()
    class Act(val label: String, val detail: () -> String = { "" }, val run: () -> Unit) : Row()
    class Info(val label: String, val text: () -> String) : Row()
    /** Barra de uso (p. ej. almacenamiento): texto y fracción usada, evaluados al construir la página. */
    class Bar(val label: String, val text: () -> String, val used: () -> Float) : Row()
    class Input(val hint: String, val initial: String, val search: Boolean, val onSubmit: (String) -> Unit) : Row()
    /** Contenedor de filas que se llenan después (resultados de búsqueda). */
    class Slot : Row()
}

class Page(val title: String, val rows: List<Row>, val onShow: (() -> Unit)? = null)

/**
 * Panel de ajustes de Shelf: tarjeta central con lista navegable con D-pad, páginas apiladas (BACK sube un nivel).
 * Mismo estilo que el panel "Hoy" (scrim oscuro, tarjeta translúcida de 20 dp); el foco usa el acento vigente.
 */
class SettingsPanel(ctx: Context) : FrameLayout(ctx) {

    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium")
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        addView(list, ViewGroup.LayoutParams(-1, -2))
    }
    private val stack = ArrayList<Page>()
    private val focusBgs = ArrayList<GradientDrawable>()
    private val sections = ArrayList<TextView>()
    private val slots = HashMap<Row.Slot, LinearLayout>()
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        visibility = GONE
        setBackgroundColor(0xF50B0B10.toInt())
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(14))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = ctx.dpf(20f) }
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(8) })
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card, LayoutParams(ctx.dp(640), ctx.dp(470), Gravity.CENTER))
        Theme.listeners.add { refreshAccent() }
    }

    val isOpen get() = visibility == VISIBLE
    fun currentTitle() = stack.lastOrNull()?.title.orEmpty()

    fun open(root: Page) {
        stack.clear()
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
        push(root)
    }

    fun close() {
        hideIme()
        animate().cancel()
        visibility = GONE
        stack.clear()
        list.removeAllViews()
    }

    fun push(p: Page) {
        hideIme()
        stack.add(p)
        build(p)
    }

    /** Sube un nivel. Devuelve false si ya estaba en la raíz (el llamador cierra el panel). */
    fun back(): Boolean {
        hideIme()
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        build(stack.last())
        return true
    }

    /** Reconstruye la página actual (tras cambiar valores que afectan a otras filas). */
    fun rebuild() {
        val f = findFocus()
        val idx = if (f != null) rowIndexOf(f) else -1
        build(stack.last(), idx)
    }

    private fun rowIndexOf(v: View): Int {
        var c: View? = v
        while (c != null && c.parent !== list) c = c.parent as? View
        return if (c == null) -1 else list.indexOfChild(c)
    }

    fun fillSlot(slot: Row.Slot, rows: List<Row>) {
        val box = slots[slot] ?: return
        box.removeAllViews()
        for (r in rows) addRow(box, r)
    }

    private fun hideIme() { imm.hideSoftInputFromWindow(windowToken, 0) }

    private fun build(p: Page, focusIdx: Int = -1) {
        title.text = p.title
        list.removeAllViews()
        focusBgs.clear(); sections.clear(); slots.clear()
        for (r in p.rows) addRow(list, r)
        p.onShow?.invoke()
        // Foco: fila pedida o primera enfocable
        val target = if (focusIdx in 0 until list.childCount) list.getChildAt(focusIdx) else null
        post {
            val v = target?.takeIf { it.isFocusable } ?: firstFocusable(list)
            v?.requestFocus()
            scroll.scrollTo(0, if (focusIdx > 0) scroll.scrollY else 0)
        }
    }

    /** Primer control accionable (los textos informativos son enfocables solo para poder hacer scroll). */
    private fun firstFocusable(g: ViewGroup): View? = firstFocusable(g, true) ?: firstFocusable(g, false)

    private fun firstFocusable(g: ViewGroup, actionable: Boolean): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.isFocusable && (!actionable || c.isClickable)) return c
            if (c is ViewGroup) firstFocusable(c, actionable)?.let { return it }
        }
        return null
    }

    private fun rowBg(): GradientDrawable {
        val bg = GradientDrawable().apply { setColor(Theme.withAlpha(Theme.accent, 0x99)); cornerRadius = context.dpf(10f) }
        focusBgs.add(bg)
        return bg
    }

    private fun focusable(v: View) {
        v.isFocusable = true
        v.isClickable = true
        v.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rowBg())
            addState(intArrayOf(), ColorDrawable(0))
        }
    }

    private fun refreshAccent() {
        for (b in focusBgs) b.setColor(Theme.withAlpha(Theme.accent, 0x99))
        for (s in sections) s.setTextColor(Theme.light)
    }

    private fun addRow(parent: LinearLayout, r: Row) {
        val ctx = context
        when (r) {
            is Row.Section -> parent.addView(tv(ctx, 13f, Theme.light, "sans-serif-medium").apply {
                text = r.title.uppercase(); letterSpacing = 0.08f
                sections.add(this)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(4); leftMargin = ctx.dp(8) })
            is Row.Choice -> {
                val value = tv(ctx, 16f, Ui.TEXT2).apply { gravity = Gravity.END }
                val row = LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                }
                focusable(row)
                row.addView(tv(ctx, 16f, Ui.TEXT).apply { text = r.label }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(value, LinearLayout.LayoutParams(-2, -2))
                fun show() {
                    val i = r.get().coerceIn(0, r.options.size - 1)
                    value.text = "‹  ${r.options[i]}  ›"
                    value.setTextColor(Ui.TEXT)
                }
                fun step(d: Int) {
                    val n = r.options.size
                    r.set((r.get() + d).mod(n)); show()
                }
                show()
                row.setOnClickListener { step(1) }
                row.setOnKeyListener { _, code, e ->
                    if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                        else -> false
                    }
                }
                parent.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(44)))
            }
            is Row.Act -> {
                val value = tv(ctx, 15f, Ui.TEXT2).apply { gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(12), 0, ctx.dp(12), 0) }
                focusable(row)
                row.addView(tv(ctx, 16f, Ui.TEXT).apply { text = r.label; maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
                value.text = r.detail()
                row.addView(value, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(12) })
                row.setOnClickListener { r.run(); value.text = r.detail() }
                parent.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(44)))
            }
            is Row.Info -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6)) }
                focusable(row)
                row.addView(tv(ctx, 13f, Ui.TEXT2).apply { text = r.label })
                row.addView(tv(ctx, 15f, Ui.TEXT).apply { text = r.text(); setLineSpacing(0f, 1.1f) })
                row.isClickable = false
                parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            is Row.Bar -> {
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(8)) }
                focusable(box); box.isClickable = false
                box.addView(tv(ctx, 13f, Ui.TEXT2).apply { text = r.label })
                box.addView(tv(ctx, 15f, Ui.TEXT).apply { text = r.text() })
                val f = r.used().coerceIn(0f, 1f)
                val bar = LinearLayout(ctx).apply {
                    background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = ctx.dpf(4f) }
                    addView(View(ctx).apply { background = GradientDrawable().apply { setColor(if (f > 0.9f) 0xFFE5534B.toInt() else Theme.accent); cornerRadius = ctx.dpf(4f) } },
                        LinearLayout.LayoutParams(0, -1, f.coerceAtLeast(0.02f)))
                    addView(View(ctx), LinearLayout.LayoutParams(0, -1, 1f - f.coerceAtLeast(0.02f)))
                }
                box.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(8)).apply { topMargin = ctx.dp(6) })
                parent.addView(box, LinearLayout.LayoutParams(-1, -2))
            }
            is Row.Input -> {
                val et = EditText(ctx).apply {
                    setText(r.initial); setSelection(r.initial.length)
                    hint = r.hint
                    setHintTextColor(0x88FFFFFF.toInt())
                    setTextColor(Ui.TEXT); textSize = 18f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    setSingleLine(true)
                    imeOptions = if (r.search) EditorInfo.IME_ACTION_SEARCH else EditorInfo.IME_ACTION_DONE
                    setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused),
                            GradientDrawable().apply { setColor(0x33FFFFFF); setStroke(ctx.dp(2), Theme.accent); cornerRadius = ctx.dpf(10f) })
                        addState(intArrayOf(), GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = ctx.dpf(10f) })
                    }
                    setOnEditorActionListener { v, _, _ -> hideIme(); r.onSubmit(v.text.toString().trim()); true }
                    // OK sobre el campo abre el teclado del sistema
                    setOnClickListener { imm.showSoftInput(this, 0) }
                }
                parent.addView(et, LinearLayout.LayoutParams(-1, ctx.dp(48)).apply { topMargin = ctx.dp(4) })
            }
            is Row.Slot -> {
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                slots[r] = box
                parent.addView(box, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
}
