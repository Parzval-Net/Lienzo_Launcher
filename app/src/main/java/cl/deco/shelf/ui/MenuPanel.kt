package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Menú contextual simple de una app (Agregar/Quitar favoritas, Mover, Info). */
class MenuPanel(ctx: Context) : FrameLayout(ctx) {

    private val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        background = GradientDrawable().apply { setColor(0xF0202028.toInt()); cornerRadius = ctx.dpf(18f) }
    }
    private val title = tv(ctx, 18f, Ui.TEXT, "sans-serif-medium")

    init {
        visibility = GONE
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        addView(box, LayoutParams(ctx.dp(320), -2, Gravity.CENTER))
    }

    private fun option(text: String, onClick: () -> Unit): TextView = tv(context, 16f).apply {
        this.text = text
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx().dp(14), 0, ctx().dp(14), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = ctx().dpf(10f) })
            addState(intArrayOf(), ColorDrawable(0))
        }
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF111111.toInt(), Ui.TEXT)))
        setOnClickListener { onClick() }
    }

    private fun ctx() = context

    fun show(label: String, isFav: Boolean, onFav: () -> Unit, onMove: () -> Unit, onInfo: () -> Unit) {
        box.removeAllViews()
        title.text = label
        title.maxLines = 1
        box.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10); leftMargin = context.dp(14) })
        val opts = listOf(
            option(if (isFav) "Quitar de favoritas" else "Agregar a favoritas", onFav),
            option("Mover", onMove),
            option("Info de la app", onInfo),
        )
        for (o in opts) box.addView(o, LinearLayout.LayoutParams(-1, context.dp(44)))
        visibility = VISIBLE
        opts[0].requestFocus()
    }

    fun hide() { visibility = GONE }
}
