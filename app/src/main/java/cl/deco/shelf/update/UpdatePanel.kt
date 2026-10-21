package cl.deco.shelf.update

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cl.deco.shelf.ui.PageUi
import cl.deco.shelf.ui.Theme
import cl.deco.shelf.ui.Ui
import cl.deco.shelf.ui.dp
import cl.deco.shelf.ui.dpf
import cl.deco.shelf.ui.tv

/**
 * Panel "Actualizar Lienzo": versión nueva con notas, descarga con progreso y cancelación, verificación e instalación.
 * Mismo estilo que Salud del deco. Sin servicios: todo ocurre mientras el panel está abierto. Nunca instala sin que el
 * usuario confirme en la pantalla de Android.
 */
class UpdatePanel(ctx: Context) : FrameLayout(ctx), UpdateInstaller.Listener {

    private enum class Phase { INFO, NEED_PERMISSION, DOWNLOADING, VERIFYING, AWAITING, ERROR }

    /** "Más tarde": el host oculta el aviso 24 h y cierra el panel. */
    var onSnooze: () -> Unit = {}
    /** Cerrar el panel (el host devuelve el foco). */
    var onClose: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium")
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFocusable = false
        addView(body, android.view.ViewGroup.LayoutParams(-1, -2))
    }
    private var info: UpdateInfo? = null
    private var phase = Phase.INFO
    private var message = ""
    private var installer: UpdateInstaller? = null
    // Vistas vivas de la descarga (se actualizan sin reconstruir)
    private var barFill: View? = null
    private var barRest: View? = null
    private var progressText: TextView? = null

    val isOpen get() = visibility == VISIBLE

    /** Debe sobrevivir a onStop del host: la pantalla de Android (ajustes o confirmación) tapa al home mientras tanto. */
    val keepAcrossStop get() = isOpen && (phase == Phase.AWAITING || phase == Phase.NEED_PERMISSION)

    init {
        visibility = GONE
        setBackgroundColor(0xF50B0B10.toInt())
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(14))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = ctx.dpf(20f) }
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(6) })
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card, LayoutParams(ctx.dp(640), ctx.dp(470), Gravity.CENTER))
    }

    fun open(i: UpdateInfo) {
        release()
        info = i; phase = Phase.INFO; message = ""
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
        build()
    }

    fun close() {
        animate().cancel()
        release()
        visibility = GONE
        body.removeAllViews()
        barFill = null; barRest = null; progressText = null
    }

    private fun release() {
        installer?.release()
        installer = null
    }

    /** El host volvió a primer plano (p. ej. desde la pantalla de permiso de Android). */
    fun onHostStart() {
        if (isOpen && phase == Phase.NEED_PERMISSION) {
            phase = Phase.INFO
            message = if (canInstall()) "Permiso concedido. Pulsa Actualizar ahora." else "Falta autorizar la instalación desde Lienzo."
            build()
        }
    }

    /**
     * BACK: durante la descarga la cancela y vuelve al resumen; en el resumen cierra (devuelve false al host).
     * Con la confirmación de Android pendiente, solo cierra.
     */
    fun back(): Boolean {
        when (phase) {
            Phase.DOWNLOADING, Phase.VERIFYING -> { cancelDownload(); return true }
            Phase.NEED_PERMISSION, Phase.ERROR -> { phase = Phase.INFO; message = ""; build(); return true }
            else -> return false
        }
    }

    // ---- Construcción ----

    private fun label(t: String, size: Float = 14f, color: Int = Ui.TEXT2) = tv(context, size, color).apply { text = t; setLineSpacing(0f, 1.1f) }

    private fun section(t: String) = tv(context, 13f, Theme.light, "sans-serif-medium").apply { text = t.uppercase(); letterSpacing = 0.08f }

    private fun build() {
        val ctx = context
        val i = info ?: return
        body.removeAllViews()
        barFill = null; barRest = null; progressText = null
        title.text = "Actualizar Lienzo"
        val m = LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(8) }
        var first: View? = null
        fun add(v: View, h: Int = ctx.dp(42)) { body.addView(v, LinearLayout.LayoutParams(-1, h)); if (first == null) first = v }

        when (phase) {
            Phase.INFO -> {
                body.addView(infoRow("Versión instalada", installedName()))
                body.addView(infoRow("Versión nueva", UpdateRules.displayVersion(i.versionName)))
                body.addView(infoRow("Tamaño", mb(i.apkSize)))
                if (i.notes.isNotBlank()) {
                    body.addView(section("Novedades"), m)
                    body.addView(label(i.notes.trim(), 14f, Ui.TEXT), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(4) })
                }
                body.addView(label("Lienzo descarga el APK desde GitHub, verifica su SHA-256 y su firma y recién entonces pide instalarlo. Android siempre te pide confirmar.", 13f),
                    LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(10) })
                if (message.isNotEmpty()) body.addView(label(message, 13f, Ui.TEXT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6); leftMargin = ctx.dp(8) })
                body.addView(section("Acciones"), m)
                add(action("Actualizar ahora", "") { startUpdate() })
                add(action("Más tarde", "Oculta el aviso 24 h") { onSnooze() })
            }
            Phase.NEED_PERMISSION -> {
                body.addView(label("Para instalar la actualización, Android debe permitir a Lienzo instalar apps. Se pide una sola vez.", 15f, Ui.TEXT), m)
                body.addView(label("En la pantalla que se abre, activa \"Permitir de esta fuente\" para Lienzo y vuelve con el botón Atrás. Si no aparece esa pantalla: Ajustes de Android > Seguridad y restricciones > Instalar apps desconocidas > Lienzo.", 14f),
                    LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(10) })
                add(action("Abrir ajustes de instalación", "") { openUnknownSources() }, ctx.dp(48))
                add(action("Volver", "") { back() }, ctx.dp(48))
            }
            Phase.DOWNLOADING, Phase.VERIFYING -> {
                body.addView(label(if (phase == Phase.DOWNLOADING) "Descargando ${UpdateRules.displayVersion(i.versionName)}" else "Verificando SHA-256 y firma...", 16f, Ui.TEXT), m)
                val fill = View(ctx).apply { background = GradientDrawable().apply { setColor(Theme.accent); cornerRadius = ctx.dpf(4f) } }
                val rest = View(ctx)
                barFill = fill; barRest = rest
                val bar = LinearLayout(ctx).apply {
                    background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = ctx.dpf(4f) }
                    addView(fill, LinearLayout.LayoutParams(0, -1, 0.001f)); addView(rest, LinearLayout.LayoutParams(0, -1, 0.999f))
                }
                body.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(8)).apply { topMargin = ctx.dp(12); leftMargin = ctx.dp(8); rightMargin = ctx.dp(8) })
                val pt = label("", 14f, Ui.TEXT); progressText = pt
                body.addView(pt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8); leftMargin = ctx.dp(8) })
                add(action("Cancelar", "") { cancelDownload() }, ctx.dp(48))
                (body.getChildAt(body.childCount - 1).layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(14)
            }
            Phase.AWAITING -> {
                body.addView(label("Confirma la instalación en la pantalla de Android.", 17f, Ui.TEXT), m)
                body.addView(label("El APK ya está verificado. Cuando aceptes, Lienzo se reinstala y se reinicia solo. Si cancelas en esa pantalla, no se cambia nada.", 14f),
                    LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(10) })
                add(action("Cerrar", "") { onClose() }, ctx.dp(48))
            }
            Phase.ERROR -> {
                body.addView(label(message, 16f, 0xFFFF8A80.toInt()).apply { setLineSpacing(0f, 1.15f) }, m)
                add(action("Reintentar", "") { startUpdate() }, ctx.dp(48))
                add(action("Cerrar", "") { onClose() }, ctx.dp(48))
            }
        }
        val target = first
        post { target?.requestFocus(); scroll.scrollTo(0, 0) }
    }

    private fun infoRow(name: String, text: String): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(2))
            addView(label(name), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv(ctx, 15f, Ui.TEXT).apply { this.text = text })
        }
    }

    private fun action(text: String, detail: String, onClick: () -> Unit): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            isFocusable = true; isClickable = true
            background = PageUi.focusBg(ctx)
            addView(tv(ctx, 16f, Ui.TEXT).apply { this.text = text; maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
            addView(tv(ctx, 14f, Ui.TEXT2).apply { this.text = detail; gravity = Gravity.END; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(12) })
            setOnClickListener { onClick() }
        }
    }

    // ---- Flujo ----

    @Suppress("DEPRECATION")
    private fun installedName() = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let { UpdateRules.displayVersion(it) } ?: "?" } catch (_: Exception) { "?" }

    private fun canInstall() = context.packageManager.canRequestPackageInstalls()

    private fun mb(b: Long) = if (b > 0) "%.1f MB".format(b / 1048576.0) else "?"

    private fun startUpdate() {
        val i = info ?: return
        if (!canInstall()) { phase = Phase.NEED_PERMISSION; build(); return }
        phase = Phase.DOWNLOADING; message = ""
        build()
        release()
        installer = UpdateInstaller(context, this).also { it.start(i) }
    }

    private fun cancelDownload() {
        installer?.cancel()
        release()
        phase = Phase.INFO; message = "Descarga cancelada. No se instaló nada."
        build()
    }

    /** Pantalla pública de "instalar apps desconocidas" de Lienzo; si este firmware no la tiene, ajustes de seguridad o generales. */
    private fun openUnknownSources() {
        val ctx = context
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (it in candidates) {
            try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
        Toast.makeText(ctx, "No se pudo abrir los ajustes. Ve a Ajustes de Android > Seguridad y restricciones.", Toast.LENGTH_LONG).show()
    }

    // ---- UpdateInstaller.Listener (siempre en el hilo principal) ----

    override fun onProgress(done: Long, total: Long) {
        if (!isOpen || phase != Phase.DOWNLOADING) return
        val f = if (total > 0) (done.toFloat() / total).coerceIn(0.001f, 1f) else 0.001f
        (barFill?.layoutParams as? LinearLayout.LayoutParams)?.weight = f
        (barRest?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f - f + 0.0001f
        barFill?.requestLayout()
        progressText?.text = "${mb(done)} de ${mb(total)}" + if (total > 0) " (${(done * 100 / total).toInt()}%)" else ""
    }

    override fun onVerifying() {
        if (!isOpen) return
        phase = Phase.VERIFYING; build()
    }

    override fun onAwaitingConfirm() {
        if (!isOpen) return
        phase = Phase.AWAITING; build()
    }

    override fun onError(message: String) {
        if (!isOpen) return
        this.message = message; phase = Phase.ERROR; build()
    }
}
