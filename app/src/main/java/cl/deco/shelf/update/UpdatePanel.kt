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
import cl.deco.shelf.service.SelfSetup
import cl.deco.shelf.ui.Glass
import cl.deco.shelf.ui.Theme
import cl.deco.shelf.ui.Ui
import cl.deco.shelf.ui.dp
import cl.deco.shelf.ui.dpf
import cl.deco.shelf.ui.t
import cl.deco.shelf.ui.tv

/**
 * Panel "Actualizar Lienzo": versión nueva con notas, descarga con progreso y cancelación, verificación e instalación.
 * Mismo estilo de vidrio ahumado que Ajustes y Salud del deco. Sin servicios: todo ocurre mientras el panel está abierto.
 */
class UpdatePanel(ctx: Context) : FrameLayout(ctx), UpdateInstaller.Listener {

    private enum class Phase { INFO, NEED_PERMISSION, DOWNLOADING, VERIFYING, AWAITING, ERROR }

    /** "Más tarde": el host oculta el aviso 24 h y cierra el panel. */
    var onSnooze: () -> Unit = {}
    /** Cerrar el panel (el host devuelve el foco). */
    var onClose: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium")
    private val footer = tv(ctx, 13f, Ui.TEXT2).apply { gravity = Gravity.CENTER; alpha = 0.8f }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ctx.dp(20))
        setPadding(0, 0, ctx.dp(6), 0)
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        addView(body, android.view.ViewGroup.LayoutParams(-1, -2))
    }
    private var info: UpdateInfo? = null
    private var phase = Phase.INFO
    private var message = ""
    private var adbStatus = ""
    private var adbBusy = false
    private var leftForSettings = false
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
        setBackgroundColor(Glass.SCRIM)
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(18), ctx.dp(20), ctx.dp(14))
            background = Glass.card(ctx).apply { setColor(Glass.CARD_SOLID) }
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(10) })
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(View(ctx).apply { setBackgroundColor(Glass.DIVIDER) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = ctx.dp(8) })
        card.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        val dm = ctx.resources.displayMetrics
        val cardW = ctx.dp(680).coerceAtMost((dm.widthPixels * 0.84f).toInt())
        val cardH = ctx.dp(480).coerceAtMost((dm.heightPixels * 0.90f).toInt())
        addView(card, LayoutParams(cardW, cardH, Gravity.CENTER))
    }

    fun open(i: UpdateInfo) {
        release()
        info = i; phase = Phase.INFO; message = ""; adbStatus = ""; adbBusy = false; leftForSettings = false
        animate().cancel()
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).setInterpolator(Ui.DECEL).withLayer().start()
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

    /**
     * El host volvió a primer plano (onStart u onResume: en Android TV `ExternalSourcesActivity` abre como panel lateral
     * traslúcido y solo dispara onPause/onResume, no onStop/onStart). Si ya tiene permiso, arranca la descarga directo.
     */
    fun onHostResume() {
        if (!isOpen || phase != Phase.NEED_PERMISSION) return
        if (canInstall()) {
            leftForSettings = false
            startUpdate(force = true)
        } else if (leftForSettings) {
            leftForSettings = false
            adbStatus = t(
                "Android aún reporta el permiso inactivo para Lienzo (algunos Google TV ocultan el launcher actual en esa lista). Puedes autorizarlo abajo con ADB local o descargar igual.",
                "Android still reports install permission off for Lienzo (some Google TVs hide the active Home app there). Use local ADB below or download anyway.",
            )
            build()
        }
    }

    fun onHostStart() = onHostResume()

    /**
     * BACK: durante la descarga la cancela y vuelve al resumen; en el resumen cierra (devuelve false al host).
     * Con la confirmación de Android pendiente, solo cierra.
     */
    fun back(): Boolean {
        when (phase) {
            Phase.DOWNLOADING, Phase.VERIFYING -> { cancelDownload(); return true }
            Phase.NEED_PERMISSION, Phase.ERROR -> { phase = Phase.INFO; message = ""; adbStatus = ""; build(); return true }
            else -> return false
        }
    }

    // ---- Construcción ----

    private fun label(txt: String, size: Float = 14f, color: Int = Ui.TEXT2) = tv(context, size, color).apply { text = txt; setLineSpacing(0f, 1.1f) }

    private fun section(txt: String) = tv(context, 13f, Theme.light, "sans-serif-medium").apply { text = txt.uppercase(); letterSpacing = 0.08f }

    private fun build() {
        val ctx = context
        val i = info ?: return
        body.removeAllViews()
        barFill = null; barRest = null; progressText = null
        title.text = t("Actualizar Lienzo", "Update Lienzo")
        footer.text = when (phase) {
            Phase.DOWNLOADING, Phase.VERIFYING -> t("OK  cancela     BACK  cancela y vuelve", "OK  cancel     BACK  cancel & back")
            Phase.NEED_PERMISSION, Phase.ERROR -> t("OK  selecciona     BACK  vuelve", "OK  select     BACK  back")
            else -> t("OK  selecciona     BACK  cierra", "OK  select     BACK  close")
        }
        val m = LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(10) }
        var first: View? = null
        fun add(v: View, h: Int = ctx.dp(44)) {
            body.addView(v, LinearLayout.LayoutParams(-1, h).apply { topMargin = ctx.dp(6) })
            if (first == null) first = v
        }

        when (phase) {
            Phase.INFO -> {
                val meta = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ctx.dp(6), ctx.dp(4), ctx.dp(6), ctx.dp(4))
                    background = Glass.groupCard(ctx, 14f)
                    addView(infoRow(t("Versión instalada", "Installed version"), installedName()))
                    addView(View(ctx).apply { setBackgroundColor(Glass.DIVIDER) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = ctx.dp(10); rightMargin = ctx.dp(10) })
                    addView(infoRow(t("Versión nueva", "New version"), UpdateRules.displayVersion(i.versionName)))
                    addView(View(ctx).apply { setBackgroundColor(Glass.DIVIDER) }, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = ctx.dp(10); rightMargin = ctx.dp(10) })
                    addView(infoRow(t("Tamaño", "Size"), mb(i.apkSize)))
                }
                body.addView(meta, LinearLayout.LayoutParams(-1, -2))
                if (i.notes.isNotBlank()) {
                    body.addView(section(t("Novedades", "What's new")), m)
                    body.addView(label(i.notes.trim(), 14f, Ui.TEXT), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(4) })
                }
                body.addView(label(t(
                    "Lienzo descarga el APK desde GitHub, verifica su SHA-256 y su firma y recién entonces pide instalarlo. Android siempre te pide confirmar.",
                    "Lienzo downloads the APK from GitHub, verifies its SHA-256 and signing certificate, and then opens the Android installer prompt.",
                ), 13f), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(10) })
                if (message.isNotEmpty()) body.addView(label(message, 13f, Ui.TEXT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6); leftMargin = ctx.dp(8) })
                body.addView(section(t("Acciones", "Actions")), m)
                add(action(t("Actualizar ahora", "Update now"), "") { startUpdate() })
                add(action(t("Más tarde", "Later"), t("Oculta el aviso 24 h", "Snoozes notice for 24 h")) { onSnooze() })
            }
            Phase.NEED_PERMISSION -> {
                body.addView(label(t(
                    "Android requiere autorizar a Lienzo en \"Instalar apps desconocidas\". En algunos televisores Google TV/TCL el launcher activo no aparece en esa lista.",
                    "Android requires allowing Lienzo under \"Install unknown apps\". Some Google TV/TCL models hide the active launcher from that list.",
                ), 14f, Ui.TEXT), m)
                if (adbStatus.isNotEmpty()) {
                    body.addView(label(adbStatus, 13f, Theme.light), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(8) })
                }
                body.addView(section(t("Opciones", "Options")), m)
                add(action(
                    if (adbBusy) t("Autorizando por ADB local...", "Authorizing via local ADB...") else t("Autorizar sin salir (ADB local)", "Authorize via local ADB"),
                    t("Con depuración por red activa", "Requires network debugging"),
                ) { authorizeViaLocalAdb() }, ctx.dp(44))
                add(action(t("Abrir ajustes de instalación", "Open install settings"), t("Permitir de esta fuente", "Allow from this source")) { openUnknownSources() }, ctx.dp(44))
                add(action(t("Ya lo activé, descargar igual", "Already enabled, download anyway"), t("Verifica firma y abre el instalador", "Verifies signature & opens installer")) { startUpdate(force = true) }, ctx.dp(44))
                add(action(t("Volver", "Back"), "") { back() }, ctx.dp(44))
            }
            Phase.DOWNLOADING, Phase.VERIFYING -> {
                body.addView(label(
                    if (phase == Phase.DOWNLOADING) t("Descargando ${UpdateRules.displayVersion(i.versionName)}", "Downloading ${UpdateRules.displayVersion(i.versionName)}")
                    else t("Verificando SHA-256 y firma...", "Verifying SHA-256 and signature..."),
                    16f, Ui.TEXT,
                ), m)
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
                add(action(t("Cancelar", "Cancel"), "") { cancelDownload() }, ctx.dp(48))
                (body.getChildAt(body.childCount - 1).layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(14)
            }
            Phase.AWAITING -> {
                body.addView(label(t("Confirma la instalación en la pantalla de Android.", "Confirm installation on the Android prompt."), 17f, Ui.TEXT), m)
                body.addView(label(t(
                    "El APK ya está verificado. Cuando aceptes, Lienzo se reinstala y se reinicia solo. Si cancelas en esa pantalla, no se cambia nada.",
                    "APK verified. Once you confirm, Lienzo updates and restarts automatically. Cancelling leaves everything untouched.",
                ), 14f), LinearLayout.LayoutParams(-1, -2).apply { leftMargin = ctx.dp(8); topMargin = ctx.dp(10) })
                add(action(t("Cerrar", "Close"), "") { onClose() }, ctx.dp(48))
            }
            Phase.ERROR -> {
                body.addView(label(message, 16f, 0xFFFF8A80.toInt()).apply { setLineSpacing(0f, 1.15f) }, m)
                add(action(t("Reintentar", "Retry"), "") { startUpdate(force = true) }, ctx.dp(48))
                add(action(t("Cerrar", "Close"), "") { onClose() }, ctx.dp(48))
            }
        }
        val target = first
        post { target?.requestFocus(); scroll.scrollTo(0, 0) }
    }

    private fun infoRow(name: String, text: String): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            addView(label(name), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv(ctx, 15f, Ui.TEXT).apply { this.text = text })
        }
    }

    private fun action(text: String, detail: String, onClick: () -> Unit): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            isFocusable = true; isClickable = true
            background = Glass.bg(ctx, 12f, Glass.IDLE)
            addView(Glass.inRow(tv(ctx, 16f).apply { this.text = text; maxLines = 1 }), LinearLayout.LayoutParams(-2, -2))
            addView(Glass.inRow(tv(ctx, 14f).apply { this.text = detail; gravity = Gravity.END; maxLines = 1 }, Ui.TEXT2), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(12) })
            setOnClickListener { onClick() }
        }
    }

    // ---- Flujo ----

    @Suppress("DEPRECATION")
    private fun installedName() = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let { UpdateRules.displayVersion(it) } ?: "?" } catch (_: Exception) { "?" }

    private fun canInstall() = context.packageManager.canRequestPackageInstalls()

    private fun mb(b: Long) = if (b > 0) "%.1f MB".format(b / 1048576.0) else "?"

    private fun startUpdate(force: Boolean = false) {
        val i = info ?: return
        if (!force && !canInstall()) { phase = Phase.NEED_PERMISSION; build(); return }
        phase = Phase.DOWNLOADING; message = ""; adbStatus = ""
        build()
        release()
        installer = UpdateInstaller(context, this).also { it.start(i) }
    }

    private fun authorizeViaLocalAdb() {
        if (adbBusy) return
        adbBusy = true
        adbStatus = t("Conectando a ADB local...", "Connecting to local ADB...")
        build()
        val appCtx = context.applicationContext
        Thread({
            val res = SelfSetup.grantInstallPermission(appCtx) {
                main.post {
                    if (isOpen) {
                        adbStatus = t("Acepta \"Permitir depuración por USB\" en la TV", "Accept \"Allow USB debugging\" on TV")
                        build()
                    }
                }
            }
            main.post {
                if (!isOpen) return@post
                adbBusy = false
                if (canInstall()) {
                    startUpdate(force = true)
                } else {
                    adbStatus = res
                    build()
                }
            }
        }, "lienzo-upd-adb").apply { isDaemon = true }.start()
    }

    private fun cancelDownload() {
        installer?.cancel()
        release()
        phase = Phase.INFO; message = t("Descarga cancelada. No se instaló nada.", "Download cancelled. Nothing was installed.")
        build()
    }

    /** Pantalla pública de "instalar apps desconocidas" de Lienzo; si este firmware no la tiene, ajustes de seguridad o generales. */
    private fun openUnknownSources() {
        val ctx = context
        leftForSettings = true
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (it in candidates) {
            try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
        leftForSettings = false
        Toast.makeText(ctx, t("No se pudo abrir los ajustes. Usa 'Autorizar sin salir (ADB local)'.", "Could not open Settings. Try 'Authorize via local ADB'."), Toast.LENGTH_LONG).show()
    }

    // ---- UpdateInstaller.Listener (siempre en el hilo principal) ----

    override fun onProgress(done: Long, total: Long) {
        if (!isOpen || phase != Phase.DOWNLOADING) return
        val f = if (total > 0) (done.toFloat() / total).coerceIn(0.001f, 1f) else 0.001f
        (barFill?.layoutParams as? LinearLayout.LayoutParams)?.weight = f
        (barRest?.layoutParams as? LinearLayout.LayoutParams)?.weight = 1f - f + 0.0001f
        barFill?.requestLayout()
        progressText?.text = "${mb(done)} ${t("de", "of")} ${mb(total)}" + if (total > 0) " (${(done * 100 / total).toInt()}%)" else ""
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
