package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Menú contextual de una app (Agregar/Quitar favoritas, Mover, Renombrar, Desinstalar, Info) y listas simples (Entradas,
 * apps ocultas). El foco usa el acento vigente, igual que Ajustes y las píldoras de la barra superior.
 */
class MenuPanel(ctx: Context) : FrameLayout(ctx) {

    /** Opción de lista: texto, detalle opcional a la derecha (p. ej. estado de una entrada) y acción. */
    class Opt(val label: String, val detail: String? = null, val run: () -> Unit)

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        background = Glass.card(ctx)
    }
    private val title = tv(ctx, 18f, Ui.TEXT, "sans-serif-medium").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }

    init {
        visibility = GONE
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        addView(box, LayoutParams(context.dp(380), -2, Gravity.CENTER))
    }

    /** Opción con el estilo común (foco blanco, texto oscuro); "Desinstalar" usa rojo con texto blanco. */
    private fun option(text: String, detail: String?, onClick: () -> Unit, highlight: Boolean = false): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = if (!highlight) Glass.bg(context, 14f, 0) else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0xFFD32F2F.toInt()); cornerRadius = context.dpf(14f) })
                addState(intArrayOf(), ColorDrawable(0))
            }
            setOnClickListener { onClick() }
        }
        val label = tv(context, 16f).apply { this.text = text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        row.addView(if (highlight) label else Glass.inRow(label), LinearLayout.LayoutParams(0, -2, 1f))
        if (!detail.isNullOrEmpty()) row.addView(Glass.inRow(tv(context, 14f).apply { this.text = detail; maxLines = 1 }, Ui.TEXT2),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.dp(12) })
        return row
    }

    private fun reset(label: String) {
        box.removeAllViews()
        title.text = label
        box.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10); leftMargin = context.dp(14) })
    }

    /** Fundido corto con capa de hardware: la tarjeta se compone una vez, sin capas fuera de pantalla por frame. */
    private fun reveal(first: View?) {
        animate().cancel()
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(140).withLayer().start()
        first?.requestFocus()
    }

    fun show(
        label: String,
        isFav: Boolean,
        onFav: () -> Unit,
        onMove: () -> Unit,
        onInfo: () -> Unit,
        onHide: () -> Unit,
        onHidden: () -> Unit,
        onRename: () -> Unit,
        onUninstall: (() -> Unit)?,
        onSettings: () -> Unit,
        onQuick: (QuickAccess.Item) -> Unit,
    ) {
        reset(label)
        val opts = mutableListOf(
            option(if (isFav) "Quitar de favoritas" else "Agregar a favoritas", null, onFav),
            option("Mover", null, onMove),
            option("Renombrar", null, onRename),
            option("Info de la app", null, onInfo),
            option("Ocultar app", null, onHide),
            option("Ver apps ocultas", null, onHidden),
        )
        if (onUninstall != null) opts.add(option("Desinstalar app", null, onUninstall, highlight = true))
        opts.add(option("Ajustes de Lienzo", null, onSettings))
        for (o in opts) box.addView(o, LinearLayout.LayoutParams(-1, context.dp(40)))
        addQuickRow(onQuick)
        reveal(opts[0])
    }

    /** Fila de accesos rápidos del sistema (íconos enfocables) y nombre del enfocado. */
    private fun addQuickRow(onQuick: (QuickAccess.Item) -> Unit) {
        val c = context
        val name = tv(c, 13f, Ui.TEXT2).apply { text = "Accesos rápidos"; gravity = Gravity.CENTER }
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(8), 0, 0) }
        for (it in QuickAccess.items) {
            val b = android.widget.ImageView(c).apply {
                setImageResource(it.icon); imageTintList = Glass.fg()
                setPadding(c.dp(9), c.dp(9), c.dp(9), c.dp(9))
                isFocusable = true; isClickable = true
                contentDescription = it.label
                background = Glass.bg(c, 14f, 0)
                setOnFocusChangeListener { _, f -> if (f) name.text = it.label }
                setOnClickListener { _ -> onQuick(it) }
            }
            row.addView(b, LinearLayout.LayoutParams(c.dp(44), c.dp(44)))
        }
        box.addView(row, LinearLayout.LayoutParams(-1, -2))
        box.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(2) })
    }

    /** Lista simple de opciones (entradas, apps ocultas). Más de [MAX_ROWS] filas se desplazan dentro de la tarjeta. */
    fun showList(label: String, options: List<Opt>) {
        reset(label)
        val views = options.map { o -> option(o.label, o.detail, { o.run() }) }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (v in views) list.addView(v, LinearLayout.LayoutParams(-1, context.dp(44)))
        if (views.size > MAX_ROWS) {
            val sv = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                isFocusable = false
                addView(list)
            }
            box.addView(sv, LinearLayout.LayoutParams(-1, context.dp(44 * MAX_ROWS)))
        } else box.addView(list, LinearLayout.LayoutParams(-1, -2))
        reveal(views.firstOrNull())
    }

    fun hide() { animate().cancel(); visibility = GONE }

    private companion object { const val MAX_ROWS = 8 }
}
