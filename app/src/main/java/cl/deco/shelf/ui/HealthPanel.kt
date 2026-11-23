package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import cl.deco.shelf.util.DeviceHealth
import cl.deco.shelf.util.MemoryCleaner

/**
 * Panel "Salud del deco": estado (CMA, RAM, tiempo encendido), recomendación y acciones.
 * Mismo estilo que los paneles Hoy y Ajustes (scrim oscuro, tarjeta translúcida de 20 dp, foco con el acento).
 * Sin servicios ni polling: lee al abrir y 3 s después de liberar cachés. BACK vuelve de "Cómo reiniciar" y luego cierra.
 */
class HealthPanel(ctx: Context) : FrameLayout(ctx) {

    /** Soltar cachés propias (LRU de tiles y blur) al liberar cachés. */
    var onTrimCaches: () -> Unit = {}
    /** "Recordarme más tarde": el host oculta el aviso 24 h y cierra el panel. */
    var onSnooze: () -> Unit = {}
    /** Instantánea de CMA y uptime (el host puede forzar condiciones solo en builds de debug). */
    var snapshot: () -> DeviceHealth.Snapshot = { DeviceHealth.read() }
    /** Abre una pantalla de ajustes del sistema por intent público. */
    var onOpenAndroidSettings: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium")
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFocusable = false
        addView(body, android.view.ViewGroup.LayoutParams(-1, -2))
    }
    private var howTo = false
    private var freed = false
    private var busy = false
    private var result = ""
    private val afterTask = Runnable { finishFree() }
    private var before: Pair<DeviceHealth.Snapshot, DeviceHealth.Ram>? = null

    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        setBackgroundColor(Glass.SCRIM)
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(14))
            background = Glass.card(ctx)
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(6) })
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card, LayoutParams(ctx.dp(640), ctx.dp(470), Gravity.CENTER))
    }

    fun open() {
        howTo = false; freed = false; busy = false; result = ""; before = null
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
        build()
    }

    fun close() {
        main.removeCallbacks(afterTask)
        busy = false
        animate().cancel()
        visibility = GONE
        body.removeAllViews()
    }

    /** Devuelve false si ya estaba en la raíz (el host cierra el panel). */
    fun back(): Boolean {
        if (howTo) { howTo = false; build(); return true }
        return false
    }

    // ---- Construcción ----

    private fun label(t: String, size: Float = 14f, color: Int = Ui.TEXT2) = tv(context, size, color).apply { text = t; setLineSpacing(0f, 1.1f) }

    private fun section(t: String) = tv(context, 13f, Theme.light, "sans-serif-medium").apply {
        text = t.uppercase(); letterSpacing = 0.08f
    }

    private fun build() {
        val ctx = context
        body.removeAllViews()
        if (howTo) { buildHowTo(); return }
        title.text = t("Salud del equipo", "Device health")
        val s = snapshot()
        val ram = DeviceHealth.readRam(ctx)
        val reco = DeviceHealth.recommend(s, ram.availKb, ram.totalKb, freed)

        body.addView(section(t("Estado", "Status")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(4); leftMargin = ctx.dp(8) })
        body.addView(meter(t("Memoria de video (CMA)", "Video memory (CMA)"), if (s.cmaTotalKb > 0) "${DeviceHealth.mbText(s.cmaFreeKb)} ${t("libres de", "free of")} ${DeviceHealth.mbText(s.cmaTotalKb)}" else t("No disponible", "Unavailable"),
            if (s.cmaTotalKb > 0) s.cmaFreeKb.toFloat() / s.cmaTotalKb else 1f, s.cmaLow))
        body.addView(meter(t("RAM disponible", "Available RAM"), "${DeviceHealth.mbText(ram.availKb)} ${t("de", "of")} ${DeviceHealth.mbText(ram.totalKb)}",
            if (ram.totalKb > 0) ram.availKb.toFloat() / ram.totalKb else 1f, ram.totalKb > 0 && ram.availKb * 100 < ram.totalKb * DeviceHealth.RAM_LOW_PCT))
        body.addView(info(t("Encendido hace", "Uptime"), DeviceHealth.uptimeText(s.uptimeMs)))

        val recoView = tv(ctx, 16f, Ui.TEXT, "sans-serif-medium").apply {
            text = reco.text; setLineSpacing(0f, 1.1f)
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            background = GradientDrawable().apply { setColor(Theme.withAlpha(Theme.accent, 0x44)); cornerRadius = ctx.dpf(10f) }
        }
        body.addView(recoView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
        if (result.isNotEmpty()) body.addView(label(result, 13f, Ui.TEXT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6); leftMargin = ctx.dp(8) })

        body.addView(section(t("Acciones", "Actions")), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(2); leftMargin = ctx.dp(8) })
        val free = action(if (busy) t("Liberando cachés de Lienzo...", "Clearing Lienzo caches...") else t("Liberar cachés de Lienzo", "Clear Lienzo caches"), t("No cierra otras apps. Para la memoria de video, reinicia", "Won't close other apps. Reboot to clear video memory")) { liberar() }
        val killBg = action(t("Cerrar apps en segundo plano (ADB)", "Force-stop background apps (ADB)"), t("Usa LocalAdb para detener apps de usuario ociosas", "Uses LocalAdb to stop idle user apps")) { cerrarAppsFondo() }
        val how = action(t("Cómo reiniciar", "How to restart"), t("Pasos para reiniciar el deco", "Steps to reboot your TV box")) { howTo = true; build() }
        val later = action(t("Recordarme más tarde", "Remind me later"), t("Oculta el aviso 24 h", "Hides notice for 24 h")) { onSnooze() }
        for (v in listOf(free, killBg, how, later)) body.addView(v, LinearLayout.LayoutParams(-1, ctx.dp(42)))

        // Foco inicial en la acción recomendada
        val target = when (reco.action) { DeviceHealth.Action.RESTART -> how; else -> free }
        post { target.requestFocus(); scroll.scrollTo(0, 0) }
    }

    private fun meter(name: String, text: String, free: Float, low: Boolean): View {
        val ctx = context
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4)) }
        val head = LinearLayout(ctx)
        head.addView(label(name), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv(ctx, 15f, if (low) 0xFFFF8A80.toInt() else Ui.TEXT).apply { this.text = text })
        box.addView(head)
        val f = free.coerceIn(0f, 1f)
        val bar = LinearLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = ctx.dpf(4f) }
            addView(View(ctx).apply { background = GradientDrawable().apply { setColor(if (low) 0xFFE5534B.toInt() else Theme.accent); cornerRadius = ctx.dpf(4f) } },
                LinearLayout.LayoutParams(0, -1, f.coerceAtLeast(0.02f)))
            addView(View(ctx), LinearLayout.LayoutParams(0, -1, 1f - f.coerceAtLeast(0.02f)))
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(8)).apply { topMargin = ctx.dp(5) })
        return box
    }

    private fun info(name: String, text: String): View {
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
            background = Glass.bg(ctx, 14f, 0)
            addView(Glass.inRow(tv(ctx, 16f).apply { this.text = text; maxLines = 1 }), LinearLayout.LayoutParams(-2, -2))
            addView(Glass.inRow(tv(ctx, 14f).apply { this.text = detail; gravity = Gravity.END; maxLines = 1 }, Ui.TEXT2), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ctx.dp(12) })
            setOnClickListener { onClick() }
        }
    }

    // ---- Liberar cachés de Lienzo ----

    private fun liberar() {
        if (busy) return
        if (MemoryCleaner.playbackActive(context)) {
            result = t("Hay reproducción activa. Termina de ver y vuelve a intentar.", "Playback is active. Finish watching and try again.")
            build(); return
        }
        val b = snapshot() to DeviceHealth.readRam(context)
        before = b
        onTrimCaches()
        busy = true
        result = t("Liberando... el resultado aparece en 3 segundos.", "Clearing... results appear in 3 seconds.")
        build()
        main.postDelayed(afterTask, 3000)
    }

    private fun finishFree() {
        if (!isOpen) return
        busy = false; freed = true
        val b = before ?: return
        val s = snapshot(); val r = DeviceHealth.readRam(context)
        result = t("Antes", "Before") + ": RAM ${DeviceHealth.mbText(b.second.availKb)}, CMA ${DeviceHealth.mbText(b.first.cmaFreeKb)}\n" +
            t("Después", "After") + ": RAM ${DeviceHealth.mbText(r.availKb)}, CMA ${DeviceHealth.mbText(s.cmaFreeKb)}\n" +
            DeviceHealth.freeVerdict(b.second.availKb, r.availKb, b.first.cmaFreeKb, s.cmaFreeKb)
        build()
    }

    private fun cerrarAppsFondo() {
        if (busy) return
        busy = true
        val appCtx = context.applicationContext
        val b = snapshot() to DeviceHealth.readRam(context)
        result = t("Cerrando apps en segundo plano vía ADB local...", "Force-stopping background apps via local ADB...")
        build()
        Thread({
            val msg = cl.deco.shelf.service.SelfSetup.forceStopBackgroundApps(appCtx) {
                main.post { if (isOpen) { result = t("Acepta \"Permitir depuración por USB\" en la TV", "Accept \"Allow USB debugging\" on TV"); build() } }
            }
            main.postDelayed({
                if (!isOpen) return@postDelayed
                busy = false
                val s = snapshot(); val r = DeviceHealth.readRam(context)
                result = "$msg\n${t("Antes", "Before")}: RAM ${DeviceHealth.mbText(b.second.availKb)}, CMA ${DeviceHealth.mbText(b.first.cmaFreeKb)} · " +
                    "${t("Ahora", "Now")}: RAM ${DeviceHealth.mbText(r.availKb)}, CMA ${DeviceHealth.mbText(s.cmaFreeKb)}"
                build()
            }, 1500)
        }, "lienzo-kill-bg").apply { isDaemon = true }.start()
    }

    // ---- Cómo reiniciar ----

    private fun buildHowTo() {
        val ctx = context
        title.text = t("Cómo reiniciar el deco", "How to restart your TV box")
        body.addView(label(t("Reiniciar libera la memoria de video fragmentada. Elige una opción:", "Restarting clears fragmented video memory. Choose an option:"), 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(8); leftMargin = ctx.dp(8) })
        body.addView(step(1, t("Desde el control", "Using the remote"), t("Mantén presionado el botón de encendido y elige Reiniciar.", "Press and hold the power button, then choose Restart.")))
        body.addView(step(2, t("Desde Ajustes de Android", "From Android Settings"), t("Ajustes > Preferencias del dispositivo > Reiniciar (último elemento de la lista).", "Settings > Device Preferences > Restart (bottom of the list).")))
        val open = action(t("Abrir Ajustes de Android", "Open Android Settings"), "") { onOpenAndroidSettings() }
        body.addView(open, LinearLayout.LayoutParams(-1, ctx.dp(48)).apply { leftMargin = ctx.dp(44); topMargin = ctx.dp(4) })
        body.addView(step(3, t("Desenchufar", "Unplug"), t("Desconecta el deco de la corriente 10 segundos y vuelve a conectarlo.", "Unplug the power cable for 10 seconds, then plug it back in.")))
        val back = action(t("Volver", "Back"), "") { back() }
        body.addView(back, LinearLayout.LayoutParams(-1, ctx.dp(48)).apply { topMargin = ctx.dp(10) })
        post { open.requestFocus(); scroll.scrollTo(0, 0) }
    }

    private fun step(n: Int, head: String, text: String): View {
        val ctx = context
        val badge = tv(ctx, 16f, 0xFF111111.toInt(), "sans-serif-medium").apply {
            this.text = "$n"; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.light) }
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(ctx, 16f, Ui.TEXT, "sans-serif-medium").apply { this.text = head })
        col.addView(label(text, 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        return LinearLayout(ctx).apply {
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(6))
            addView(badge, LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)).apply { rightMargin = ctx.dp(12) })
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}
