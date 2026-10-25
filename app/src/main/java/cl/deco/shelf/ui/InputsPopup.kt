package cl.deco.shelf.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Desplegable de Entradas anclado bajo la píldora de la barra superior (estilo tvOS): ícono por tipo, foco blanco con texto
 * oscuro, punto verde en las entradas con señal y separadores entre HDMI, AV y TV en vivo. Solo Views; el fundido usa capa de
 * hardware y dura 160 ms.
 */
class InputsPopup(ctx: Context) : FrameLayout(ctx) {

    var onPick: ((TvInputs.Source) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        background = GradientDrawable().apply {
            setColor(0xE63A4354.toInt())
            cornerRadius = ctx.dpf(22f)
            setStroke(ctx.dp(1), 0x59FFFFFF)
        }
        minimumWidth = ctx.dp(260)
    }
    private val rows = ArrayList<View>()
    private var anchor: View? = null

    init {
        visibility = GONE
        isClickable = true // un clic fuera de la tarjeta no llega al home
        setOnClickListener { onClose?.invoke() }
        addView(card, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
    }

    val isOpen get() = visibility == VISIBLE

    /** Muestra [list] bajo [anchor] (borde izquierdo alineado con la píldora). */
    private fun fill(list: List<TvInputs.Source>) {
        card.removeAllViews(); rows.clear()
        var prev: TvInputs.Kind? = null
        for (s in list) {
            if (prev != null && s.kind != prev) card.addView(View(context).apply { setBackgroundColor(0x40FFFFFF) },
                LinearLayout.LayoutParams(-1, 1).apply { setMargins(context.dp(18), context.dp(6), context.dp(18), context.dp(6)) })
            prev = s.kind
            val r = row(s)
            rows += r
            card.addView(r, LinearLayout.LayoutParams(-1, context.dp(46)))
        }
    }

    /** Entradas cambiaron con el desplegable abierto (equipo CEC enchufado o retirado): filas nuevas, mismo foco, sin animar. */
    fun update(list: List<TvInputs.Source>) {
        if (!isOpen) return
        val idx = rows.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
        fill(list)
        rows.getOrNull(idx.coerceAtMost(rows.size - 1))?.requestFocus()
    }

    fun open(anchor: View, list: List<TvInputs.Source>) {
        fill(list)
        val loc = IntArray(2); val me = IntArray(2)
        anchor.getLocationOnScreen(loc); getLocationOnScreen(me)
        // Ancho fijo (las filas son match_parent): alineada con la píldora y sin salir por el borde derecho
        val w = context.dp(CARD_DP)
        (card.layoutParams as LayoutParams).apply {
            width = w
            leftMargin = (loc[0] - me[0]).coerceAtMost(resources.displayMetrics.widthPixels - w - context.dp(48))
            topMargin = loc[1] - me[1] + anchor.height + context.dp(10)
        }
        card.requestLayout()
        // La píldora queda resaltada (activada) mientras el desplegable está abierto, como en tvOS
        this.anchor = anchor; anchor.isActivated = true
        animate().cancel()
        alpha = 0f
        card.translationY = -context.dpf(8f)
        visibility = VISIBLE
        animate().alpha(1f).setDuration(160).withLayer().start()
        card.animate().translationY(0f).setDuration(160).setInterpolator(Ui.DECEL).start()
        rows.firstOrNull()?.requestFocus()
    }

    fun close() {
        anchor?.isActivated = false; anchor = null
        animate().cancel(); card.animate().cancel()
        visibility = GONE
        card.removeAllViews(); rows.clear()
    }

    /** UP en la primera fila cierra (vuelve a la píldora); los costados no sacan el foco del desplegable. */
    fun handleKey(e: KeyEvent): Boolean {
        val i = rows.indexOfFirst { it.hasFocus() }
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (i == 0) { if (e.action == KeyEvent.ACTION_DOWN) onClose?.invoke(); true } else false }
            KeyEvent.KEYCODE_DPAD_DOWN -> i == rows.size - 1
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }

    private fun row(s: TvInputs.Source): View {
        val c = context
        val fg = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(FOCUS_TEXT, Ui.TEXT))
        val fg2 = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF4A5060.toInt(), Ui.TEXT2))
        return LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(c.dp(14), 0, c.dp(18), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0xF2FFFFFF.toInt()); cornerRadius = c.dpf(16f) })
                addState(intArrayOf(), ColorDrawable(0))
            }
            addView(ImageView(c).apply {
                setImageResource(when (s.kind) {
                    TvInputs.Kind.AV -> cl.deco.shelf.R.drawable.ic_in_av
                    TvInputs.Kind.TV -> cl.deco.shelf.R.drawable.ic_in_tv
                    else -> cl.deco.shelf.R.drawable.ic_in_hdmi
                })
                imageTintList = fg
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(c.dp(22), c.dp(22)))
            addView(tv(c, 17f, Ui.TEXT, "sans-serif-medium").apply {
                text = s.label; maxLines = 1
                setTextColor(fg); isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = c.dp(14) })
            // "Conectado": cable HDMI puesto o equipo CEC (ver TvInputs.sources); sin eso, se marca la última usada
            if (s.connected) {
                addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF34C759.toInt()) } },
                    LinearLayout.LayoutParams(c.dp(8), c.dp(8)).apply { leftMargin = c.dp(16) })
                addView(tv(c, 14f, Ui.TEXT2).apply { text = "Conectado"; setTextColor(fg2); isDuplicateParentStateEnabled = true },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = c.dp(8) })
            } else if (s.last) {
                addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.TEXT2) } },
                    LinearLayout.LayoutParams(c.dp(6), c.dp(6)).apply { leftMargin = c.dp(16) })
                addView(tv(c, 14f, Ui.TEXT2).apply { text = "Última usada"; setTextColor(fg2); isDuplicateParentStateEnabled = true },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = c.dp(8) })
            }
            setOnClickListener { onPick?.invoke(s) }
        }
    }

    private companion object {
        const val FOCUS_TEXT = 0xFF2A2F3A.toInt()
        const val CARD_DP = 330
    }
}
