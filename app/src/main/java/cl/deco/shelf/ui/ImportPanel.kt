package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.data.art.MediaImporter
import cl.deco.shelf.data.art.MediaItem
import cl.deco.shelf.data.art.SpaceRules
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Importar desde USB": grilla de miniaturas (imágenes y videos de MediaStore, internos y USB) navegable con D-pad,
 * selección múltiple con OK, resumen de tamaño y espacio, progreso cancelable y resultado con los rechazos.
 * Mismo estilo que los demás paneles. Lee solo MediaStore (permiso estándar de medios); sin red ni servicios.
 */
class ImportPanel(ctx: Context) : FrameLayout(ctx) {

    /** El panel pide cerrarse (el host devuelve el foco). */
    var onClose: () -> Unit = {}
    /** Se importó algo: refrescar los ajustes y el fondo. */
    var onImported: () -> Unit = {}

    private enum class Mode { LOADING, LIST, EMPTY, WORKING, RESULT }

    private val main = Handler(Looper.getMainLooper())
    private val importer = MediaImporter(ctx)
    private val title = tv(ctx, 22f, Ui.TEXT, "sans-serif-medium")
    private val subtitle = tv(ctx, 14f, Ui.TEXT2)
    private val body = FrameLayout(ctx)
    private var mode = Mode.LOADING

    private var items: List<MediaItem> = emptyList()
    private var imported: Set<String> = emptySet()
    private val selected = LinkedHashSet<android.net.Uri>()
    private var freeBytes = 0L
    private var changedSomething = false
    private val cancelFlag = AtomicBoolean(false)
    private var worker: Thread? = null
    private var gen = 0

    private val thumbs = Executors.newSingleThreadExecutor { r -> Thread(r, "lienzo-thumbs").apply { priority = Thread.MIN_PRIORITY } }
    private val thumbSignals = HashSet<CancellationSignal>()
    private val cache = object : LruCache<android.net.Uri, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: android.net.Uri, value: Bitmap) = value.byteCount
    }

    private var rv: RecyclerView? = null
    private var summary: TextView? = null
    private var importChip: TextView? = null
    private var allChip: TextView? = null

    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        setBackgroundColor(0xFF0B0B10.toInt()) // opaco: los ajustes quedan debajo y no deben traslucirse
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(16), ctx.dp(22), ctx.dp(14))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = ctx.dpf(20f) }
        }
        val head = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(subtitle, LinearLayout.LayoutParams(-2, -2))
        card.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(8) })
        card.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card, LayoutParams(ctx.dp(880), ctx.dp(500), Gravity.CENTER))
    }

    // ---- Ciclo de vida ----

    fun open() {
        selected.clear(); items = emptyList(); changedSomething = false
        alpha = 0f
        visibility = VISIBLE
        animate().alpha(1f).setDuration(180).start()
        load()
    }

    fun close() {
        gen++
        cancelFlag.set(true)
        synchronized(thumbSignals) { thumbSignals.forEach { it.cancel() }; thumbSignals.clear() }
        animate().cancel()
        visibility = GONE
        body.removeAllViews()
        rv?.adapter = null
        rv = null; summary = null; importChip = null; allChip = null
        cache.evictAll()
        items = emptyList()
    }

    /** true si BACK se consumió dentro del panel; false = el host debe cerrar. */
    fun back(): Boolean {
        if (mode == Mode.WORKING) { cancelFlag.set(true); return true }
        if (changedSomething) { changedSomething = false; onImported() }
        return false
    }

    // ---- Carga de la lista ----

    private fun load() {
        mode = Mode.LOADING
        title.text = "Importar imágenes y videos"
        subtitle.text = ""
        showMessage("Buscando imágenes y videos...", emptyList())
        val g = ++gen
        Thread({
            val found = try { importer.query() } catch (_: Throwable) { emptyList() }
            val keys = try { cl.deco.shelf.data.art.UserArt(context).importedKeys() } catch (_: Throwable) { emptySet() }
            val free = importer.freeBytes()
            main.post {
                if (g != gen || !isOpen) return@post
                items = found; imported = keys; freeBytes = free
                if (found.isEmpty()) showEmpty() else showList()
            }
        }, "lienzo-import-scan").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    // ---- Vistas ----

    private fun label(t: String, size: Float = 15f, color: Int = Ui.TEXT) = tv(context, size, color).apply { text = t; setLineSpacing(0f, 1.15f) }

    private fun showMessage(text: String, chips: List<TextView>) {
        body.removeAllViews()
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(label(text, 16f), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(14) })
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
        chips.forEach { row.addView(it) }
        col.addView(row)
        body.addView(col, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = context.dp(30); rightMargin = context.dp(30) })
        chips.firstOrNull()?.post { chips.first().requestFocus() }
    }

    private fun showEmpty() {
        mode = Mode.EMPTY
        subtitle.text = ""
        showMessage(
            "No hay imágenes ni videos para importar.\nConecta un pendrive USB con fotos (JPG, PNG, WebP) o videos H.264/H.265 de hasta 1080p y 60 fps. " +
                "Si ya lo conectaste, espera unos segundos a que Android lo indexe y pulsa Actualizar.",
            listOf(PageUi.chip(context, "Actualizar", 130) { load() }, PageUi.chip(context, "Volver", 110) { onClose() }),
        )
    }

    private fun showList() {
        mode = Mode.LIST
        body.removeAllViews()
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val list = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, COLS)
            adapter = Adapter()
            overScrollMode = OVER_SCROLL_NEVER
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(8)
        }
        rv = list
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val sum = tv(ctx, 14f, Ui.TEXT2).apply { maxLines = 2; setLineSpacing(0f, 1.1f) }
        summary = sum
        val imp = PageUi.chip(ctx, "Importar (0)", 150) { startImport() }
        val all = PageUi.chip(ctx, "Marcar todo", 140) { toggleAll() }
        importChip = imp; allChip = all
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(sum, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ctx.dp(8) })
        bar.addView(all)
        bar.addView(imp)
        bar.addView(PageUi.chip(ctx, "Actualizar", 120) { selected.clear(); load() })
        bar.addView(PageUi.chip(ctx, "Cerrar", 100) { back(); onClose() })
        col.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(44)).apply { topMargin = ctx.dp(6) })
        body.addView(col, LayoutParams(-1, -1))
        refreshSummary()
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun selectedItems() = items.filter { it.uri in selected }

    private fun refreshSummary() {
        val sel = selectedItems()
        val videoBytes = sel.filter { it.isVideo }.sumOf { it.size }
        val imgs = sel.count { !it.isVideo }
        val total = SpaceRules.estimate(imgs, videoBytes)
        val videos = items.count { it.isVideo }
        subtitle.text = "${items.size - videos} imágenes, $videos videos"
        importChip?.text = "Importar (${sel.size})"
        allChip?.text = if (selected.size == items.size) "Quitar marcas" else "Marcar todo"
        val chk = SpaceRules.check(total, freeBytes)
        val s = summary ?: return
        if (sel.isEmpty()) {
            s.setTextColor(Ui.TEXT2)
            s.text = "OK marca o desmarca. Libre: ${SpaceRules.format(freeBytes)} (se reservan 500 MB)"
        } else {
            s.setTextColor(if (chk.ok) Ui.TEXT else 0xFFFF8A80.toInt())
            s.text = "Total: ${SpaceRules.format(total)}" + (if (imgs > 0) " (imágenes estimadas)" else "") +
                (if (chk.ok) ". Libre: ${SpaceRules.format(freeBytes)}" else ". Sin espacio: faltan ${SpaceRules.format(chk.shortBy)} (se reservan 500 MB)")
        }
    }

    private fun toggleAll() {
        if (selected.size == items.size) selected.clear() else selected.addAll(items.map { it.uri })
        rv?.adapter?.notifyDataSetChanged()
        refreshSummary()
    }

    // ---- Importación ----

    private fun startImport() {
        val sel = selectedItems()
        if (sel.isEmpty()) { Toast.makeText(context, "Marca al menos un archivo con OK", Toast.LENGTH_SHORT).show(); return }
        mode = Mode.WORKING
        title.text = "Importando"
        subtitle.text = ""
        cancelFlag.set(false)
        val ctx = context
        body.removeAllViews()
        val phase = tv(ctx, 18f, Ui.TEXT, "sans-serif-medium")
        val name = tv(ctx, 15f, Ui.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val sizeText = tv(ctx, 15f, Ui.TEXT)
        val fill = View(ctx).apply { background = GradientDrawable().apply { setColor(Theme.accent); cornerRadius = ctx.dpf(4f) } }
        val rest = View(ctx)
        val bar = LinearLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = ctx.dpf(4f) }
            addView(fill, LinearLayout.LayoutParams(0, -1, 0.001f))
            addView(rest, LinearLayout.LayoutParams(0, -1, 0.999f))
        }
        val cancel = PageUi.chip(ctx, "Cancelar", 140) { cancelFlag.set(true) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(phase, LinearLayout.LayoutParams(-2, -2))
        col.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
        col.addView(bar, LinearLayout.LayoutParams(-1, ctx.dp(8)).apply { topMargin = ctx.dp(16) })
        col.addView(sizeText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
        col.addView(cancel, LinearLayout.LayoutParams(ctx.dp(140), ctx.dp(40)).apply { topMargin = ctx.dp(22) })
        body.addView(col, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = ctx.dp(60); rightMargin = ctx.dp(60) })
        cancel.post { cancel.requestFocus() }

        val g = gen
        var last = 0L
        worker = Thread({
            val out = try {
                importer.run(sel, cancelFlag) { p ->
                    val now = System.currentTimeMillis()
                    if (now - last < 120 && p.doneBytes != 0L) return@run
                    last = now
                    main.post {
                        if (g != gen || mode != Mode.WORKING) return@post
                        phase.text = if (cancelFlag.get()) "Cancelando..." else "${p.phase} ${p.index} de ${p.total}"
                        name.text = p.name
                        val f = if (p.totalBytes > 0) (p.doneBytes.toFloat() / p.totalBytes).coerceIn(0.001f, 0.999f) else 0.001f
                        (fill.layoutParams as LinearLayout.LayoutParams).weight = f
                        (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
                        bar.requestLayout()
                        sizeText.text = if (p.totalBytes > 0) "${SpaceRules.format(p.doneBytes)} de ${SpaceRules.format(p.totalBytes)}" else ""
                    }
                }
            } catch (t: Throwable) {
                MediaImporter.Outcome(failed = listOf("Importación" to (t.message ?: t.javaClass.simpleName)))
            }
            main.post { if (g == gen && isOpen) showResult(out) }
        }, "lienzo-import").apply { priority = Thread.MIN_PRIORITY; isDaemon = true; start() }
    }

    private fun showResult(o: MediaImporter.Outcome) {
        mode = Mode.RESULT
        if (o.images + o.videos > 0) changedSomething = true
        title.text = when {
            o.noSpace != null -> "Sin espacio suficiente"
            o.cancelled -> "Importación cancelada"
            o.images + o.videos > 0 -> "Importación lista"
            else -> "No se importó nada"
        }
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(8)) }
        fun line(t: String, color: Int = Ui.TEXT, size: Float = 15f) =
            col.addView(label(t, size, color), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
        o.noSpace?.let {
            line("Se necesitan ${SpaceRules.format(it.needed)} y quedan ${SpaceRules.format(it.free)} libres. Lienzo deja al menos 500 MB libres: faltan ${SpaceRules.format(it.shortBy)}.", 0xFFFF8A80.toInt())
            line("Libera espacio en el deco o elige menos archivos.", Ui.TEXT2)
        }
        if (o.cancelled) line("Se detuvo la copia y se borró el archivo parcial.", Ui.TEXT2)
        if (o.images + o.videos > 0) line("Importados: ${o.images} ${if (o.images == 1) "imagen" else "imágenes"} y ${o.videos} ${if (o.videos == 1) "video" else "videos"}.")
        if (o.rejected.isNotEmpty()) {
            line("Rechazados por formato:", 0xFFFFC27A.toInt())
            o.rejected.forEach { (n, why) -> line("• $n\n   $why", Ui.TEXT2, 14f) }
        }
        if (o.skipped.isNotEmpty()) line("Ya importados (se omiten): ${o.skipped.joinToString(", ")}", Ui.TEXT2, 14f)
        if (o.failed.isNotEmpty()) {
            line("Con error:", 0xFFFF8A80.toInt())
            o.failed.forEach { (n, why) -> line("• $n: $why", Ui.TEXT2, 14f) }
        }
        if (o.noSpace == null && !o.cancelled && o.images + o.videos == 0 && o.rejected.isEmpty() && o.failed.isEmpty() && o.skipped.isEmpty()) line("No había nada nuevo que importar.", Ui.TEXT2)
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFocusable = false; addView(col, ViewGroup.LayoutParams(-1, -2)) }
        val done = PageUi.chip(ctx, "Listo", 120) { back(); onClose() }
        val again = PageUi.chip(ctx, "Importar más", 150) { selected.clear(); load(); title.text = "Importar imágenes y videos" }
        val row = LinearLayout(ctx).apply { gravity = Gravity.END }
        row.addView(again); row.addView(done)
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        outer.addView(row, LinearLayout.LayoutParams(-1, ctx.dp(44)).apply { topMargin = ctx.dp(6) })
        body.removeAllViews()
        body.addView(outer, LayoutParams(-1, -1))
        done.post { done.requestFocus() }
    }

    // ---- Grilla ----

    private inner class Cell(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        val img = root.getChildAt(0) as ImageView
        val check = root.getChildAt(1) as CheckView
        val name = root.getChildAt(2) as TextView
        val meta = root.getChildAt(3) as TextView
        val tag = root.getChildAt(4) as TextView
    }

    private inner class Adapter : RecyclerView.Adapter<Cell>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val ctx = parent.context
            val root = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(ctx.dp(CELL_W), ctx.dp(CELL_H))
                isFocusable = true; isClickable = true
                val ring = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0); setStroke(ctx.dp(3), Theme.accent); cornerRadius = ctx.dpf(8f) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(0); cornerRadius = ctx.dpf(8f) })
                }
                foreground = ring
                background = GradientDrawable().apply { setColor(0x33000000); cornerRadius = ctx.dpf(8f) }
                setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            root.addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, LayoutParams(-1, -1))
            root.addView(CheckView(ctx), LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.TOP or Gravity.END).apply { topMargin = ctx.dp(6); rightMargin = ctx.dp(6) })
            val shade = 0xAA000000.toInt()
            root.addView(tv(ctx, 11f, Ui.TEXT, "sans-serif-medium").apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), 0); setBackgroundColor(shade)
            }, LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ctx.dp(14) })
            root.addView(tv(ctx, 10f, Ui.TEXT2).apply {
                maxLines = 1; setPadding(ctx.dp(6), 0, ctx.dp(6), ctx.dp(2)); setBackgroundColor(shade)
            }, LayoutParams(-1, ctx.dp(14), Gravity.BOTTOM))
            root.addView(tv(ctx, 10f, 0xFF111111.toInt(), "sans-serif-medium").apply {
                setPadding(ctx.dp(6), ctx.dp(1), ctx.dp(6), ctx.dp(1))
                background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = ctx.dpf(6f) }
            }, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = ctx.dp(6); leftMargin = ctx.dp(6) })
            return Cell(root)
        }

        override fun onBindViewHolder(h: Cell, position: Int) {
            val it = items[position]
            h.name.text = it.name
            h.meta.text = if (it.isVideo) "Video ${dur(it.durationMs)} · ${it.width}x${it.height} · ${SpaceRules.format(it.size)}"
            else "${it.width}x${it.height} · ${SpaceRules.format(it.size)}"
            h.tag.text = when { it.key in imported && !it.isVideo -> "Importada"; it.isUsb -> "USB"; else -> "" }
            h.tag.visibility = if (h.tag.text.isEmpty()) View.GONE else View.VISIBLE
            h.check.checked = it.uri in selected
            h.root.setOnClickListener { _ ->
                val pos = h.bindingAdapterPosition.takeIf { p -> p >= 0 } ?: return@setOnClickListener
                val u = items[pos].uri
                if (!selected.remove(u)) selected.add(u)
                h.check.checked = u in selected
                refreshSummary()
            }
            h.img.tag = it.uri
            val c = cache.get(it.uri)
            if (c != null) h.img.setImageBitmap(c) else { h.img.setImageDrawable(null); loadThumb(h.img, it) }
        }
    }

    private fun loadThumb(iv: ImageView, it: MediaItem) {
        val g = gen
        try {
            thumbs.execute {
                if (g != gen || iv.tag != it.uri) return@execute // la celda ya se reutilizó o el panel se cerró
                val sig = CancellationSignal()
                synchronized(thumbSignals) { thumbSignals.add(sig) }
                val bmp = try { context.contentResolver.loadThumbnail(it.uri, Size(THUMB_W, THUMB_H), sig) } catch (_: Throwable) { null }
                synchronized(thumbSignals) { thumbSignals.remove(sig) }
                if (bmp == null) return@execute
                main.post {
                    if (g != gen) return@post
                    cache.put(it.uri, bmp)
                    if (iv.tag == it.uri) iv.setImageBitmap(bmp)
                }
            }
        } catch (_: Exception) { /* executor cerrado */ }
    }

    private fun dur(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    /** Círculo de selección con tilde, dibujado a mano (sin recursos ni fuentes). */
    private class CheckView(ctx: Context) : View(ctx) {
        var checked = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            p.style = Paint.Style.FILL
            p.color = if (checked) Theme.accent else 0x99000000.toInt()
            c.drawCircle(r, r, r - 1f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = width / 12f; p.color = 0xFFFFFFFF.toInt()
            c.drawCircle(r, r, r - 1.5f, p)
            if (checked) {
                p.strokeWidth = width / 9f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                path.reset(); path.moveTo(width * 0.28f, height * 0.52f); path.lineTo(width * 0.44f, height * 0.68f); path.lineTo(width * 0.74f, height * 0.34f)
                c.drawPath(path, p)
            }
        }
    }

    private companion object {
        const val COLS = 4
        const val CELL_W = 205
        const val CELL_H = 116
        const val THUMB_W = 320
        const val THUMB_H = 180
    }
}
