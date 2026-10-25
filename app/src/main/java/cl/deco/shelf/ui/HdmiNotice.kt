package cl.deco.shelf.ui

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Aviso "Dispositivo conectado" al enchufar un equipo en un HDMI, con el estilo de Entradas: tarjeta de vidrio arriba a
 * la derecha (bajo la barra superior), ícono HDMI, título, puerto o nombre CEC y dos píldoras (Cambiar / Ahora no). Entra
 * deslizándose con capa de hardware y se oculta sola a los 12 s si no se usa.
 */
class HdmiNotice(ctx: Context) : FrameLayout(ctx) {

    var onSwitch: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val title = tv(ctx, 18f, Ui.TEXT, "sans-serif-medium").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val sub = tv(ctx, 15f, Ui.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val switchBtn = pill("Cambiar") { onSwitch?.invoke() }
    private val laterBtn = pill("Ahora no") { onDismiss?.invoke() }
    private val autoHide = Runnable { if (isOpen) onDismiss?.invoke() }

    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(20), ctx.dp(18), ctx.dp(20), ctx.dp(18))
        background = Glass.card(ctx)
        val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(ctx).apply {
            setImageResource(cl.deco.shelf.R.drawable.ic_in_hdmi)
            setColorFilter(Ui.TEXT)
            setPadding(ctx.dp(9), ctx.dp(9), ctx.dp(9), ctx.dp(9))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Glass.SELECTED) }
        }, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        }
        head.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(14) })
        addView(head, LinearLayout.LayoutParams(-1, -2))
        val buttons = LinearLayout(ctx).apply { gravity = Gravity.END }
        buttons.addView(laterBtn, LinearLayout.LayoutParams(-2, ctx.dp(40)))
        buttons.addView(switchBtn, LinearLayout.LayoutParams(-2, ctx.dp(40)).apply { leftMargin = ctx.dp(10) })
        addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
    }

    init {
        visibility = GONE
        isClickable = false // el home detrás sigue visible; las teclas las toma el foco de las píldoras
        addView(card, LayoutParams(ctx.dp(400), -2, Gravity.TOP or Gravity.END).apply { topMargin = ctx.dp(104); rightMargin = ctx.dp(48) })
    }

    val isOpen get() = visibility == VISIBLE

    private fun pill(text: String, onClick: () -> Unit) = tv(context, 15f, Ui.TEXT, "sans-serif-medium").apply {
        this.text = text
        gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        setPadding(context.dp(20), 0, context.dp(20), 0)
        background = Glass.bg(context, 20f)
        setTextColor(Glass.fg())
        setOnClickListener { onClick() }
    }

    /** [heading] "Dispositivo conectado" o el nombre CEC del equipo; [detail] el puerto ("HDMI 3"). */
    fun show(heading: String, detail: String, port: String) {
        title.text = heading
        sub.text = detail
        switchBtn.text = "Cambiar a $port"
        removeCallbacks(autoHide)
        animate().cancel(); card.animate().cancel()
        alpha = 0f
        card.translationX = context.dpf(40f)
        visibility = VISIBLE
        animate().alpha(1f).setDuration(200).withLayer().start()
        card.animate().translationX(0f).setDuration(220).setInterpolator(Ui.DECEL).start()
        switchBtn.requestFocus()
        postDelayed(autoHide, 12_000L)
    }

    fun hide() {
        removeCallbacks(autoHide)
        animate().cancel(); card.animate().cancel()
        visibility = GONE
    }

    /** Las píldoras solo se mueven entre sí: el foco no escapa al home mientras el aviso está abierto. */
    fun handleKey(e: KeyEvent): Boolean = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
        KeyEvent.KEYCODE_DPAD_LEFT -> { if (e.action == KeyEvent.ACTION_DOWN) laterBtn.requestFocus(); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { if (e.action == KeyEvent.ACTION_DOWN) switchBtn.requestFocus(); true }
        else -> false
    }
}
