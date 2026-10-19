package cl.deco.shelf.clock

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cl.deco.shelf.ui.Ui
import cl.deco.shelf.ui.dp
import cl.deco.shelf.ui.dpf
import cl.deco.shelf.ui.tv
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tarjeta de alarma o temporizador a pantalla completa. Cualquier tecla detiene; mantener OK (DPAD_CENTER o ENTER)
 * 1 s pospone 5 min. OK decide en ACTION_UP (o al detectar el long-press), el resto detiene en ACTION_DOWN.
 */
class AlarmCard(ctx: Context, title: String, label: String, private val onStop: () -> Unit, private val onSnooze: () -> Unit) : FrameLayout(ctx) {

    private var okLong = false
    private var done = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xE60B0B10.toInt())
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(56), ctx.dp(36), ctx.dp(56), ctx.dp(36))
            background = GradientDrawable().apply { setColor(0xFF1B1E27.toInt()); cornerRadius = ctx.dpf(28f) }
        }
        card.addView(tv(ctx, 22f, 0xFFFF9A3C.toInt(), "sans-serif-medium").apply { text = title.uppercase(); letterSpacing = 0.12f })
        card.addView(tv(ctx, 88f, Ui.TEXT, "sans-serif-light").apply { text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        if (label.isNotBlank()) card.addView(tv(ctx, 28f, Ui.TEXT).apply { text = label },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(6) })
        card.addView(tv(ctx, 16f, Ui.TEXT2).apply { text = "Pulsa cualquier botón para detener · Mantén OK para posponer 5 min" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(28) })
        addView(card, LayoutParams(-2, -2, Gravity.CENTER))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (done) return true
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok) {
            if (e.action == KeyEvent.ACTION_DOWN && (e.repeatCount > 0 || e.isLongPress)) okLong = true
            if (e.action == KeyEvent.ACTION_UP) { finish(if (okLong) onSnooze else onStop) }
        } else if (e.action == KeyEvent.ACTION_DOWN) finish(onStop)
        return true
    }

    private fun finish(f: () -> Unit) { if (!done) { done = true; f() } }
}
