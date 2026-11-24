package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Filas de una página del panel de ajustes. */
sealed class Row {
    class Section(val title: String) : Row()
    /** Opciones cíclicas: IZQUIERDA/DERECHA o OK cambian el valor. Con [isToggle] se muestra como interruptor. */
    class Choice(val label: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit, val isToggle: Boolean = false) : Row()
    class Act(val label: String, val detail: () -> String = { "" }, val icon: Int = 0, val chevron: Boolean = false, val run: () -> Unit) : Row()
    class Info(val label: String, val text: () -> String) : Row()
    /** Barra de uso (p. ej. almacenamiento): texto y fracción usada, evaluados al construir la página. */
    class Bar(val label: String, val text: () -> String, val used: () -> Float) : Row()
    class Input(val hint: String, val initial: String, val search: Boolean, val onSubmit: (String) -> Unit) : Row()
    /** Contenedor de filas que se llenan después (resultados de búsqueda). */
    class Slot : Row()
}

class Page(val title: String, val rows: List<Row>, val onShow: (() -> Unit)? = null)

/**
 * Panel de ajustes de Shelf: tarjeta central de vidrio ahumado navegable con D-pad, secciones agrupadas,
 * interruptores visuales y páginas apiladas (BACK sube un nivel).
 */
class SettingsPanel(ctx: Context) : FrameLayout(ctx) {

    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    /** Ruta de páginas ("Ajustes › Pantalla y fondos"): con varias subpáginas se sabe dónde se está y adónde vuelve BACK. */
    private val crumb = tv(ctx, 13f, Ui.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START; visibility = GONE }
    private val footer = tv(ctx, 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER; alpha = 0.8f }
    private val themeListener: () -> Unit = { refreshAccent() }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(24))
        setPadding(0, 0, ctx.dp(6), 0)
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        addView(list, ViewGroup.LayoutParams(-1, -2))
    }
    private val stack = ArrayList<Page>()
    /** Fila enfocada de cada página al entrar a una subpágina, para volver al mismo lugar con BACK. */
    private val focusStack = ArrayList<Int>()
    private val sections = ArrayList<TextView>()
    private val toggleRepaints = ArrayList<() -> Unit>()
    private val barFills = ArrayList<GradientDrawable>()
    private val slots = HashMap<Row.Slot, LinearLayout>()
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        visibility = GONE
        setBackgroundColor(Glass.SCRIM)
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(18), ctx.dp(20), ctx.dp(14))
            // Vidrio ahumado casi opaco: con tanto texto la barra superior no debe transparentarse detrás
            background = Glass.card(ctx).apply { setColor(Glass.CARD_SOLID) }
        }
        card.addView(crumb, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(2) })
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(10) })
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(View(ctx).apply { setBackgroundColor(Glass.DIVIDER) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = ctx.dp(8) })
        card.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        val dm = ctx.resources.displayMetrics
        val cardW = ctx.dp(740).coerceAtMost((dm.widthPixels * 0.86f).toInt())
        val cardH = ctx.dp(492).coerceAtMost((dm.heightPixels * 0.91f).toInt())
        addView(card, LayoutParams(cardW, cardH, Gravity.CENTER))
    }

    // El listener vive solo mientras el panel está en pantalla: con recreate() la Activity anterior no queda retenida por Theme
    override fun onAttachedToWindow() { super.onAttachedToWindow(); Theme.listeners.add(themeListener); refreshAccent() }
    override fun onDetachedFromWindow() { Theme.listeners.remove(themeListener); super.onDetachedFromWindow() }

    val isOpen get() = visibility == VISIBLE
    fun currentTitle() = stack.lastOrNull()?.title.orEmpty()

    fun open(root: Page) {
        stack.clear(); focusStack.clear()
        animate().cancel()
        alpha = 0f
        visibility = VISIBLE
        // Capa de hardware durante el fundido: el panel se compone una vez y la GPU solo mezcla su alpha (60 fps en Mali-450)
        animate().alpha(1f).setDuration(180).setInterpolator(Ui.DECEL).withLayer().start()
        push(root)
    }

    fun close() {
        hideIme()
        animate().cancel()
        scroll.animate().cancel()
        scroll.alpha = 1f; scroll.translationX = 0f
        visibility = GONE
        stack.clear(); focusStack.clear()
        list.removeAllViews()
    }

    fun push(p: Page) {
        hideIme()
        val hasParent = stack.isNotEmpty()
        if (hasParent) focusStack.add(findFocus()?.let { rowIndexOf(it) } ?: -1)
        stack.add(p)
        build(p, dir = if (hasParent) 1 else 0)
    }

    /** Sube un nivel. Devuelve false si ya estaba en la raíz (el llamador cierra el panel). */
    fun back(): Boolean {
        hideIme()
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        build(stack.last(), focusStack.removeLastOrNull() ?: -1, dir = -1)
        return true
    }

    /** Reemplaza la página actual por [p] (filas recalculadas), manteniendo la fila enfocada y la pila. */
    fun replace(p: Page) {
        if (stack.isEmpty()) { push(p); return }
        val f = findFocus()
        stack[stack.size - 1] = p
        build(p, if (f != null) rowIndexOf(f) else -1)
    }

    /** Reemplaza la pila completa (p. ej. al cambiar idioma en caliente), preservando el índice enfocado. */
    fun replacePages(pages: List<Page>) {
        if (pages.isEmpty()) return
        val f = findFocus()
        val idx = if (f != null) rowIndexOf(f) else -1
        stack.clear()
        stack.addAll(pages)
        while (focusStack.size >= stack.size) focusStack.removeAt(focusStack.size - 1)
        build(stack.last(), idx)
    }

    /** Reconstruye la página actual (tras cambiar valores que afectan a otras filas). */
    fun rebuild() {
        val f = findFocus()
        val idx = if (f != null) rowIndexOf(f) else -1
        build(stack.last(), idx)
    }

    private fun collectFocusables(g: ViewGroup, out: MutableList<View>) {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.isFocusable) out.add(c)
            else if (c is ViewGroup) collectFocusables(c, out)
        }
    }

    private fun rowIndexOf(v: View): Int {
        val all = ArrayList<View>()
        collectFocusables(list, all)
        var c: View? = v
        while (c != null && c !== list) {
            val idx = all.indexOf(c)
            if (idx >= 0) return idx
            c = c.parent as? View
        }
        return -1
    }

    fun fillSlot(slot: Row.Slot, rows: List<Row>) {
        val box = slots[slot] ?: return
        box.removeAllViews()
        if (rows.isEmpty()) { box.visibility = GONE; return }
        box.visibility = VISIBLE
        val group = newGroup(context)
        box.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        for (r in rows) addGroupedRow(group, r)
    }

    private fun hideIme() { imm.hideSoftInputFromWindow(windowToken, 0) }

    private fun build(p: Page, focusIdx: Int = -1, dir: Int = 0) {
        title.text = p.title
        if (stack.size > 1) { crumb.text = stack.dropLast(1).joinToString("  ›  ") { it.title }; crumb.visibility = VISIBLE }
        else crumb.visibility = GONE
        val hasChoice = p.rows.any { it is Row.Choice }
        footer.text = when {
            hasChoice && stack.size > 1 -> t("‹ ›  cambia el valor     OK  selecciona     BACK  vuelve", "‹ ›  change value     OK  select     BACK  back")
            hasChoice -> t("‹ ›  cambia el valor     OK  selecciona     BACK  cierra", "‹ ›  change value     OK  select     BACK  close")
            stack.size > 1 -> t("OK  selecciona     BACK  vuelve", "OK  select     BACK  back")
            else -> t("OK  selecciona     BACK  cierra", "OK  select     BACK  close")
        }
        scroll.animate().cancel()
        scroll.alpha = 1f; scroll.translationX = 0f
        list.removeAllViews()
        sections.clear(); toggleRepaints.clear(); barFills.clear(); slots.clear()

        var group: LinearLayout? = null
        fun ensureGroup(): LinearLayout = group ?: newGroup(context).also { g ->
            val prevIsSection = list.childCount > 0 && list.getChildAt(list.childCount - 1) is TextView
            list.addView(g, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = if (list.childCount == 0 || prevIsSection) 0 else context.dp(10)
            })
            group = g
        }
        for (r in p.rows) {
            when (r) {
                is Row.Section -> { group = null; addRow(list, r) }
                is Row.Input, is Row.Slot -> { group = null; addRow(list, r) }
                else -> addGroupedRow(ensureGroup(), r)
            }
        }
        p.onShow?.invoke()
        val all = ArrayList<View>()
        collectFocusables(list, all)
        val target = all.getOrNull(focusIdx)
        if (dir != 0) {
            scroll.alpha = 0f
            scroll.translationX = context.dpf(if (dir > 0) 20f else -20f)
            scroll.animate().alpha(1f).translationX(0f).setDuration(140).setInterpolator(Ui.DECEL).withLayer().start()
        }
        post {
            val v = target?.takeIf { it.isFocusable } ?: firstFocusable(list)
            v?.requestFocus()
            scroll.scrollTo(0, if (focusIdx > 0) scroll.scrollY else 0)
        }
    }

    private fun newGroup(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        background = Glass.groupCard(ctx, 14f)
    }

    private fun addGroupedRow(group: LinearLayout, r: Row) {
        if (group.childCount > 0) {
            group.addView(
                View(context).apply { setBackgroundColor(Glass.DIVIDER) },
                LinearLayout.LayoutParams(-1, 1).apply { leftMargin = context.dp(12); rightMargin = context.dp(12) },
            )
        }
        addRow(group, r)
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

    /** Fila enfocable con el estilo común: blanco con foco y textos (vía [Glass.inRow]) oscuros sobre el blanco. */
    private fun focusable(v: View) {
        v.isFocusable = true
        v.isClickable = true
        v.background = Glass.bg(context, 12f, 0)
    }

    /** Fila informativa enfocable para scroll: foco translúcido suave y textos siempre claros. */
    private fun focusableInfo(v: View) {
        v.isFocusable = true
        v.isClickable = false
        v.background = Glass.infoBg(context, 12f)
    }

    private fun refreshAccent() {
        for (s in sections) s.setTextColor(Theme.light)
        for (f in toggleRepaints) f()
        for (b in barFills) b.setColor(Theme.accent)
    }

    private fun addRow(parent: LinearLayout, r: Row) {
        val ctx = context
        when (r) {
            is Row.Section -> parent.addView(tv(ctx, 12f, Theme.light, "sans-serif-medium").apply {
                text = r.title.uppercase(); letterSpacing = 0.08f
                sections.add(this)
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = if (parent.childCount == 0) ctx.dp(2) else ctx.dp(12)
                bottomMargin = ctx.dp(5)
                leftMargin = ctx.dp(8)
            })
            is Row.Choice -> {
                val row = LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                }
                focusable(row)
                row.addView(Glass.inRow(tv(ctx, 16f).apply {
                    text = r.label
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }), LinearLayout.LayoutParams(0, -2, 1f))
                val show: (Boolean) -> Unit
                if (r.isToggle && r.options.size == 2) {
                    val descriptive = r.options.any { it !in GENERIC_TOGGLE }
                    val hint = if (descriptive) Glass.inRow(tv(ctx, 13f, Ui.TEXT2).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, Ui.TEXT2) else null
                    hint?.let { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(8); rightMargin = ctx.dp(10) }) }

                    val trackBg = GradientDrawable().apply { cornerRadius = ctx.dpf(12f) }
                    val thumb = View(ctx).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.TEXT) }
                    }
                    val track = FrameLayout(ctx).apply {
                        background = trackBg
                        addView(thumb, LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                            leftMargin = ctx.dp(3)
                        })
                    }
                    row.addView(track, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(24)).apply { leftMargin = ctx.dp(6) })

                    fun paintTrack() {
                        val on = r.get() == 0
                        if (on) {
                            trackBg.setColor(Theme.accent)
                            trackBg.setStroke(0, 0)
                        } else {
                            val f = row.isFocused
                            trackBg.setColor(if (f) 0x38161A22 else 0x38FFFFFF)
                            trackBg.setStroke(ctx.dp(1), if (f) 0x33161A22 else 0x42FFFFFF)
                        }
                    }
                    toggleRepaints.add(::paintTrack)
                    row.setOnFocusChangeListener { _, _ -> paintTrack() }
                    show = { anim ->
                        val i = r.get().coerceIn(0, 1)
                        val on = i == 0
                        hint?.text = r.options[i]
                        val tx = if (on) ctx.dpf(20f) else 0f
                        thumb.animate().cancel()
                        if (anim) thumb.animate().translationX(tx).setDuration(130).setInterpolator(Ui.DECEL).start()
                        else thumb.translationX = tx
                        paintTrack()
                    }
                } else {
                    val value = Glass.inRow(tv(ctx, 15f, Ui.TEXT, "sans-serif-medium").apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0x14161A22); cornerRadius = ctx.dpf(10f) })
                            addState(intArrayOf(), GradientDrawable().apply { setColor(0x18FFFFFF); cornerRadius = ctx.dpf(10f) })
                        }
                    })
                    row.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(12) })
                    show = { _ ->
                        val i = r.get().coerceIn(0, r.options.size - 1)
                        value.text = "‹  ${r.options[i]}  ›"
                    }
                }
                fun step(d: Int) {
                    val n = r.options.size
                    r.set((r.get() + d).mod(n)); show(true)
                }
                show(false)
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
                val value = Glass.inRow(tv(ctx, 14f).apply { gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.TEXT2)
                val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(12), 0, ctx.dp(12), 0) }
                focusable(row)
                if (r.icon != 0) {
                    row.addView(ImageView(ctx).apply {
                        setImageResource(r.icon)
                        imageTintList = Glass.fg()
                        isDuplicateParentStateEnabled = true
                    }, LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { rightMargin = ctx.dp(12) })
                }
                row.addView(Glass.inRow(tv(ctx, 16f).apply {
                    text = r.label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }), LinearLayout.LayoutParams(-2, -2))
                value.text = r.detail()
                row.addView(value, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(12) })
                if (r.chevron) {
                    row.addView(Glass.inRow(tv(ctx, 18f, Ui.TEXT2, "sans-serif-medium").apply { text = "›" }, Ui.TEXT2),
                        LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ctx.dp(10) })
                }
                row.setOnClickListener { r.run(); value.text = r.detail() }
                parent.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(44)))
            }
            is Row.Info -> {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8)) }
                focusableInfo(row)
                if (r.label.isNotEmpty()) {
                    row.addView(tv(ctx, 13f, Ui.TEXT2).apply { text = r.label }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(2) })
                }
                row.addView(tv(ctx, 15f, Ui.TEXT).apply { text = r.text(); setLineSpacing(0f, 1.15f) })
                parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            is Row.Bar -> {
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(10)) }
                focusableInfo(box)
                box.addView(tv(ctx, 13f, Ui.TEXT2).apply { text = r.label }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(2) })
                box.addView(tv(ctx, 15f, Ui.TEXT).apply { text = r.text() })
                val f = r.used().coerceIn(0f, 1f)
                val warn = f > 0.9f
                val fillBg = GradientDrawable().apply {
                    setColor(if (warn) 0xFFE5534B.toInt() else Theme.accent)
                    cornerRadius = ctx.dpf(4f)
                }.also { if (!warn) barFills.add(it) }
                val bar = LinearLayout(ctx).apply {
                    background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = ctx.dpf(4f) }
                    addView(View(ctx).apply { background = fillBg }, LinearLayout.LayoutParams(0, -1, f.coerceAtLeast(0.02f)))
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
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused),
                            GradientDrawable().apply { setColor(0x2EFFFFFF); setStroke(ctx.dp(2), Theme.accent); cornerRadius = ctx.dpf(12f) })
                        addState(intArrayOf(), GradientDrawable().apply { setColor(Glass.GROUP); setStroke(ctx.dp(1), Glass.GROUP_STROKE); cornerRadius = ctx.dpf(12f) })
                    }
                    setOnEditorActionListener { v, _, _ -> hideIme(); r.onSubmit(v.text.toString().trim()); true }
                    // OK sobre el campo abre el teclado del sistema
                    setOnClickListener { imm.showSoftInput(this, 0) }
                }
                parent.addView(et, LinearLayout.LayoutParams(-1, ctx.dp(48)).apply { topMargin = ctx.dp(4); bottomMargin = ctx.dp(4) })
            }
            is Row.Slot -> {
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = GONE }
                slots[r] = box
                parent.addView(box, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private companion object {
        val GENERIC_TOGGLE = setOf("Activado", "Desactivado", "On", "Off", "Sí", "No", "Yes", "Mostrar", "Ocultar", "Show", "Hide")
    }
}
