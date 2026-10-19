package cl.deco.shelf.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.Graph
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.AppRepository
import cl.deco.shelf.apps.BitmapUtil
import cl.deco.shelf.apps.FavoritesStore
import cl.deco.shelf.apps.ImageLoader
import cl.deco.shelf.data.art.Artwork
import cl.deco.shelf.data.holidays.Holiday
import cl.deco.shelf.data.weather.Weather
import cl.deco.shelf.shelf.TopShelfItem
import cl.deco.shelf.util.Blur
import java.time.LocalDate
import java.time.LocalTime

/** Home estilo tvOS: arte de fondo, barra superior, Top Shelf, dock de favoritas y grilla de apps. */
class MainActivity : Activity(), AppAdapter.Callbacks {

    private lateinit var graph: Graph
    private lateinit var repo: AppRepository
    private lateinit var store: FavoritesStore
    private lateinit var loader: ImageLoader
    private lateinit var adapter: AppAdapter
    private lateinit var rv: ShelfRecycler

    private lateinit var content: FrameLayout
    private lateinit var artView: ImageView
    private lateinit var blurView: ImageView
    private lateinit var topBar: TopBar
    private lateinit var topFade: View
    private lateinit var shelfView: TopShelfView
    private lateinit var today: TodayPanel
    private lateinit var menu: MenuPanel
    private lateinit var hint: android.widget.TextView

    private val main = Handler(Looper.getMainLooper())
    private val ioData = newExecutor()
    private val ioShelf = newExecutor()
    private val ioArt = newExecutor()

    private var started = false
    private var allApps: List<AppEntry> = emptyList()
    private var weather: Weather? = null
    private var lastArt = 0L
    private var lastWeather = 0L
    private var lastDate: LocalDate? = null
    private var holidayName: String? = null
    private val holidayCache = HashMap<Int, List<Holiday>>()
    private val holidayLoading = HashSet<Int>()

    private var artBmp: Bitmap? = null
    private var blurBmp: Bitmap? = null
    private var artId: String? = null
    private var collapsed = false
    private var pendingPkg: String? = null
    private var prevFocus: View? = null
    private var moving = false
    private var focusedOnce = false

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = Graph.from(this)
        repo = AppRepository(this)
        store = FavoritesStore(this)

        val sw = resources.displayMetrics.widthPixels
        val marginX = dp(48)
        val cellW = (sw - 2 * marginX) / 5
        val padX = dp(10)
        val padY = dp(12)
        val tileW = cellW - 2 * padX
        val tileH = tileW * 3 / 5
        val radius = dpf(12f)

        loader = ImageLoader(this, tileW, tileH, radius)
        adapter = AppAdapter(loader, tileW, tileH, padX, padY, dp(12), radius, this)

        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF181B26.toInt(), 0xFF0A0A10.toInt()),
        )
        window.setBackgroundDrawable(null)

        content = FrameLayout(this)
        root.addView(content, ViewGroup.LayoutParams(-1, -1))

        artView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        blurView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000, 0x00000000, 0xB0000000.toInt(), 0xE6000000.toInt())).apply {
                setColors(intArrayOf(0x99000000.toInt(), 0x00000000, 0x00000000, 0xB0000000.toInt(), 0xE6000000.toInt()), floatArrayOf(0f, 0.2f, 0.45f, 0.8f, 1f))
            }
        }
        content.addView(artView, ViewGroup.LayoutParams(-1, -1))
        content.addView(blurView, ViewGroup.LayoutParams(-1, -1))
        content.addView(scrim, ViewGroup.LayoutParams(-1, -1))

        val topY = dp(84)
        rv = ShelfRecycler(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (this@MainActivity.adapter.isGap(position)) 5 else 1
                }
            }
            this.adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(marginX, dp(200), marginX, dp(24))
            addItemDecoration(DockPanelDecoration(this@MainActivity) { this@MainActivity.adapter.favCount })
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content.addView(rv, FrameLayout.LayoutParams(-1, resources.displayMetrics.heightPixels - topY).apply { topMargin = topY })

        topFade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF0000000.toInt(), 0x00000000))
            alpha = 0f
        }
        content.addView(topFade, FrameLayout.LayoutParams(-1, dp(56)).apply { topMargin = topY })
        shelfView = TopShelfView(this, loader) { launchItem(it) }
        content.addView(shelfView, FrameLayout.LayoutParams(-1, dp(176)).apply { topMargin = dp(96) })

        topBar = TopBar(this) { openToday() }
        content.addView(topBar, FrameLayout.LayoutParams(-1, dp(64)).apply {
            topMargin = dp(18); leftMargin = dp(34); rightMargin = dp(34)
        })

        today = TodayPanel(this).apply { onNeedYear = { loadHolidays(it) } }
        menu = MenuPanel(this)
        hint = tv(this, 14f).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dpf(20f) }
            text = "Mueve con las flechas. OK para confirmar"
            visibility = View.GONE
        }
        root.addView(today, ViewGroup.LayoutParams(-1, -1))
        root.addView(menu, ViewGroup.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        started = true
        lastArt = 0L
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reloadApps(initial = true)
        main.post(tick)
    }

    override fun onStop() {
        started = false
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pkgReceiver) }
        if (today.visibility == View.VISIBLE) closeToday()
        releaseArt()
        loader.trim()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // HOME con el launcher ya visible: cerrar overlays y volver al inicio
        if (menu.visibility == View.VISIBLE) closeMenu()
        if (today.visibility == View.VISIBLE) closeToday()
        endMove()
        if (adapter.itemCount > 0) focusAt(0)
        lastArt = 0L
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) loader.trim()
    }

    override fun onDestroy() {
        ioData.shutdownNow()
        ioShelf.shutdownNow()
        ioArt.shutdownNow()
        loader.shutdown()
        super.onDestroy()
    }

    // ---------- Reloj, clima, arte (un solo Handler, tick por minuto) ----------

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val t = LocalTime.now()
            topBar.setTime("%02d:%02d".format(t.hour, t.minute))
            val d = LocalDate.now()
            if (d != lastDate) {
                lastDate = d
                topBar.setDate(Es.fecha(d))
                updateHoliday(d)
            }
            if (now - lastArt >= ART_MS) { lastArt = now; rotateArt() }
            if (now - lastWeather >= WEATHER_MS) { lastWeather = now; refreshWeather() }
            main.postDelayed(this, 60_000L - now % 60_000L + 50L)
        }
    }

    private fun refreshWeather() {
        ioData.execute {
            val w = try { graph.weather.get(false) } catch (_: Throwable) { null }
            main.post { if (started && w != null) { weather = w; topBar.setWeather(w); today.setWeather(w) } }
        }
    }

    private fun updateHoliday(d: LocalDate) {
        val list = holidayCache[d.year]
        if (list == null) { loadHolidays(d.year); return }
        holidayName = list.firstOrNull { it.date == d }?.name
        topBar.setHoliday(holidayName)
    }

    private fun loadHolidays(year: Int) {
        if (holidayCache.containsKey(year) || !holidayLoading.add(year)) return
        ioData.execute {
            val l = try { graph.holidays.forYear(year) } catch (_: Throwable) { emptyList() }
            main.post {
                holidayLoading.remove(year)
                holidayCache[year] = l
                today.setHolidays(year, l)
                lastDate?.let { if (it.year == year) updateHoliday(it) }
            }
        }
    }

    private fun rotateArt() {
        ioArt.execute {
            var a = try { graph.art.next() } catch (_: Throwable) { null }
            if (a == null) {
                // cache vacía: descarga unas pocas obras y reintenta
                try { graph.art.prefetch(3) } catch (_: Throwable) {}
                a = try { graph.art.next() } catch (_: Throwable) { null }
            }
            var bmp: Bitmap? = null
            var blur: Bitmap? = null
            if (a != null) {
                bmp = try { BitmapUtil.decodeFile(a.file.path, 1920, 1080) } catch (_: Throwable) { null }
                if (bmp != null) blur = try {
                    // Blur precalculado una vez: 960x540 y luego reducción interna de util.Blur
                    val small = Bitmap.createScaledBitmap(bmp, 960, 540, true)
                    val b = Blur.blurred(small)
                    if (b !== small) small.recycle()
                    b
                } catch (_: Throwable) { null }
            }
            val art = a
            main.post {
                if (!started || art == null || bmp == null) { bmp?.recycle(); blur?.recycle() } else applyArt(art, bmp, blur)
            }
            // repone cache para las próximas rotaciones
            if (art != null) try { graph.art.prefetch(2) } catch (_: Throwable) {}
        }
    }

    private fun applyArt(a: Artwork, bmp: Bitmap, blur: Bitmap?) {
        val old = artBmp
        val newD = BitmapDrawable(resources, bmp)
        artBmp = bmp
        artId = a.id
        if (old == null) {
            artView.setImageDrawable(newD)
            artView.alpha = 0f
            artView.animate().alpha(1f).setDuration(350).start()
        } else {
            val td = TransitionDrawable(arrayOf(BitmapDrawable(resources, old), newD))
            artView.setImageDrawable(td)
            td.startTransition(350)
            main.postDelayed({
                artView.setImageDrawable(newD)
                old.recycle()
            }, 400)
        }
        val oldBlur = blurBmp
        blurBmp = blur
        blurView.setImageBitmap(blur)
        if (oldBlur != null && oldBlur !== blur) main.postDelayed({ oldBlur.recycle() }, 400)
        shelfView.setArt(a)
    }

    private fun releaseArt() {
        artView.setImageDrawable(null)
        blurView.setImageDrawable(null)
        artBmp?.recycle(); artBmp = null
        blurBmp?.recycle(); blurBmp = null
        artId = null
    }

    // ---------- Apps ----------

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loader.trim()
            reloadApps(initial = false)
        }
    }

    private fun reloadApps(initial: Boolean) {
        ioData.execute {
            val apps = repo.load()
            run {
                // Watch Next: una sola pasada al iniciar y al cambiar paquetes
                try { graph.shelf.preload(apps.map { it.component.packageName }.distinct()) } catch (_: Throwable) {}
            }
            main.post { if (started) { allApps = apps; rebuild() } }
        }
    }

    private fun rebuild(focusPos: Int = -1) {
        var favKeys = store.favorites()
        if (favKeys == null && allApps.isNotEmpty()) {
            favKeys = store.defaults(allApps)
            store.save(favKeys, null)
        }
        val idx = HashMap<String, Int>()
        store.order().forEachIndexed { i, k -> idx[k] = i }
        val ordered = allApps.sortedBy { idx[it.key] ?: Int.MAX_VALUE }
        val byKey = allApps.associateBy { it.key }
        val favs = favKeys.orEmpty().mapNotNull { byKey[it] }.distinctBy { it.key }
        val prev = focusedPos()
        adapter.submit(favs, ordered)
        val target = when {
            focusPos >= 0 -> focusPos
            prev >= 0 -> prev.coerceAtMost(adapter.itemCount - 1)
            else -> 0
        }
        if (adapter.itemCount > 0 && (focusPos >= 0 || !focusedOnce || prev >= 0)) { focusedOnce = true; focusAt(target) }
    }

    /** Posición del tile enfocado en el RecyclerView, o -1 si el foco está fuera (barra superior, Top Shelf). */
    private fun focusedPos(): Int {
        val v = currentFocus ?: return -1
        return if (v.parent === rv) rv.getChildAdapterPosition(v) else -1
    }

    private fun focusAt(pos: Int) {
        if (pos < 0 || pos >= adapter.itemCount) return
        var p = pos
        if (adapter.isGap(p)) p++
        val target = p
        rv.scrollToPosition(target)
        rv.postOnAnimation {
            rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
    }

    // ---------- Callbacks de tiles ----------

    override fun onClick(e: AppEntry) {
        if (moving) return
        try { startActivity(repo.launchIntent(e)) } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLongClick(e: AppEntry, pos: Int) {
        if (moving) return
        openMenu(e, pos)
    }

    override fun onFocus(e: AppEntry, pos: Int) {
        val grid = adapter.isGridPos(pos)
        setCollapsed(grid)
        if (!grid) {
            pendingPkg = e.component.packageName
            main.removeCallbacks(shelfRun)
            main.postDelayed(shelfRun, 300)
        }
    }

    private fun setCollapsed(c: Boolean) {
        if (c == collapsed) return
        collapsed = c
        shelfView.collapse(c)
        topFade.animate().cancel()
        topFade.animate().alpha(if (c) 1f else 0f).setDuration(250).start()
        blurView.animate().cancel()
        blurView.animate().alpha(if (c) 1f else 0f).setDuration(250).start()
    }

    private val shelfRun = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        ioShelf.execute {
            val items = try { graph.shelf.itemsFor(pkg) } catch (_: Throwable) { emptyList() }
            main.post { if (started && pendingPkg == pkg && !collapsed) shelfView.show(items) }
        }
    }

    private fun launchItem(it: TopShelfItem) {
        val uri = it.intentUri ?: return
        try {
            startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el contenido", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Overlays ----------

    private fun blockContent(b: Boolean) {
        content.descendantFocusability = if (b) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun openToday() {
        prevFocus = currentFocus
        blockContent(true)
        today.open(weather, LocalDate.now())
    }

    private fun closeToday() {
        today.close()
        blockContent(false)
        prevFocus?.requestFocus()
    }

    private fun openMenu(e: AppEntry, pos: Int) {
        prevFocus = currentFocus
        blockContent(true)
        val fav = adapter.isFavPos(pos) || e.key in adapter.favKeys()
        menu.show(
            e.label, fav,
            onFav = { closeMenu(restore = false); toggleFav(e, pos, fav) },
            onMove = { closeMenu(); startMove(pos) },
            onInfo = {
                closeMenu()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", e.component.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            },
        )
    }

    private fun closeMenu(restore: Boolean = true) {
        menu.hide()
        blockContent(false)
        if (restore) prevFocus?.requestFocus()
    }

    private fun toggleFav(e: AppEntry, pos: Int, isFav: Boolean) {
        val favs = adapter.favKeys().toMutableList()
        if (isFav) favs.remove(e.key) else favs.add(e.key)
        store.save(favs, adapter.appKeys())
        val wasFavPos = adapter.isFavPos(pos)
        // reconstruye y deja el foco en un lugar coherente
        val newFavCount = favs.size
        rebuildThen {
            when {
                !wasFavPos -> adapter.posOf(e.key, false)
                newFavCount > 0 -> pos.coerceAtMost(newFavCount - 1)
                else -> adapter.posOf(e.key, false)
            }
        }
    }

    private fun rebuildThen(pos: () -> Int) {
        rebuild(focusPos = -1)
        main.post { focusAt(pos().coerceAtLeast(0)) }
    }

    // ---------- Modo mover ----------

    private fun startMove(pos: Int) {
        val e = adapter.entryAt(pos) ?: return
        moving = true
        adapter.movingKey = e.key
        adapter.movingFav = adapter.isFavPos(pos)
        adapter.notifyItemChanged(pos)
        hint.visibility = View.VISIBLE
        rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun endMove() {
        if (!moving) return
        moving = false
        val key = adapter.movingKey
        val fav = adapter.movingFav
        adapter.movingKey = null
        hint.visibility = View.GONE
        store.save(adapter.favKeys(), adapter.appKeys())
        if (key != null) {
            val p = adapter.posOf(key, fav)
            if (p >= 0) adapter.notifyItemChanged(p)
        }
    }

    private fun moveStep(dx: Int, dy: Int) {
        val from = focusedPos()
        if (from < 0) return
        val to = from + dx + dy * 5
        if (adapter.move(from, to)) {
            rv.scrollToPosition(to)
            rv.post { rv.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() }
        }
    }

    // ---------- Teclas ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val k = e.keyCode
        if (down) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { Nav.dx = -1; Nav.dy = 0 }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { Nav.dx = 1; Nav.dy = 0 }
                KeyEvent.KEYCODE_DPAD_UP -> { Nav.dx = 0; Nav.dy = -1 }
                KeyEvent.KEYCODE_DPAD_DOWN -> { Nav.dx = 0; Nav.dy = 1 }
            }
        }
        if (menu.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeMenu(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (today.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeToday(); return true }
            if (k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(e)
            if (down) when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> today.shift(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> today.shift(1)
            }
            return true
        }
        if (moving) {
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) moveStep(Nav.dx, Nav.dy)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (down && e.repeatCount == 0) endMove()
                    return true
                }
            }
        }
        if (k == KeyEvent.KEYCODE_MENU && down) {
            val pos = focusedPos()
            val en = if (pos >= 0) adapter.entryAt(pos) else null
            if (en != null) openMenu(en, pos)
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP && adapter.itemCount > 0) focusAt(0)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Home ya es la raíz")
    override fun onBackPressed() { /* launcher: ignorar back */ }

    private companion object {
        const val ART_MS = 30L * 60_000L
        const val WEATHER_MS = 20L * 60_000L
    }
}
