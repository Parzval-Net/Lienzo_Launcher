package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cl.deco.shelf.service.LanTransferServer

/**
 * Panel con código QR para subir fondos/videos y respaldos desde el celular en la misma red Wi-Fi.
 * Inicia un [LanTransferServer] efímero al abrir y lo apaga al cerrar o salir del home.
 */
class LanTransferPanel(ctx: Context) : FrameLayout(ctx) {

    var onClose: () -> Unit = {}
    var onMediaImported: () -> Unit = {}
    var onBackupRestored: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium")
    private val subtitle = tv(ctx, 14f, Ui.TEXT2)
    private val qrImg = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = ctx.dpf(14f) }
        setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
    }
    private val urlText = tv(ctx, 14f, Ui.TEXT, "sans-serif-medium")
    private val statusText = tv(ctx, 14f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
    private val timerText = tv(ctx, 13f, Theme.light)
    private val closeBtn = PageUi.chip(ctx, "", 130) { close(); onClose() }

    private var server: LanTransferServer? = null
    private var qrBmp: Bitmap? = null
    private val tick = object : Runnable {
        override fun run() {
            val s = server ?: return
            val left = s.remainingSeconds()
            timerText.text = t("Se cierra solo en ${left / 60}:%02d", "Auto-closes in ${left / 60}:%02d").format(left % 60)
            main.postDelayed(this, 1000L)
        }
    }

    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        setBackgroundColor(Glass.SCRIM)
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(20), ctx.dp(24), ctx.dp(18))
            background = Glass.card(ctx)
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        card.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4); bottomMargin = ctx.dp(14) })

        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(qrImg, LinearLayout.LayoutParams(ctx.dp(220), ctx.dp(220)).apply { rightMargin = ctx.dp(22) })
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        info.addView(urlText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(10) })
        info.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(10) })
        info.addView(timerText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(16) })
        info.addView(closeBtn)
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        addView(card, LayoutParams(ctx.dp(660), -2, Gravity.CENTER))
    }

    fun open() {
        stopServer()
        title.text = t("Enviar desde el celular (QR)", "Send from Phone (QR)")
        subtitle.text = t(
            "Escanea con tu celular en la misma red Wi-Fi para subir fondos, videos o restaurar respaldo.",
            "Scan from your phone on the same Wi-Fi to upload wallpapers, videos, or restore a backup.",
        )
        closeBtn.text = t("Cerrar", "Close")
        statusText.text = t("Iniciando servidor local...", "Starting local server...")
        timerText.text = ""
        urlText.text = ""

        val srv = LanTransferServer(
            context,
            onMediaImported = { onMediaImported() },
            onBackupRestored = { close(); onBackupRestored() },
            onStatus = { msg -> statusText.text = msg },
            onExpired = { if (isOpen) { close(); onClose() } },
        )
        server = srv
        val res = srv.start()
        res.fold(
            onSuccess = { u ->
                urlText.text = u
                statusText.text = t("Listo. Esperando archivos...", "Ready. Waiting for files...")
                qrBmp?.recycle()
                qrBmp = QrCode.toBitmap(QrCode.encode(u), context.dp(200))
                qrImg.setImageBitmap(qrBmp)
                qrImg.visibility = View.VISIBLE
                main.post(tick)
            },
            onFailure = { err ->
                qrImg.visibility = View.GONE
                urlText.text = t("No disponible", "Unavailable")
                statusText.text = err.message ?: "Error"
            },
        )
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
        closeBtn.post { closeBtn.requestFocus() }
    }

    fun close() {
        stopServer()
        animate().cancel()
        visibility = GONE
    }

    private fun stopServer() {
        main.removeCallbacks(tick)
        server?.stop()
        server = null
        qrImg.setImageDrawable(null)
        qrBmp?.recycle()
        qrBmp = null
    }
}
