package cl.deco.shelf.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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

/**
 * Home estilo tvOS: la obra de arte es la protagonista. En reposo solo se ven reloj, clima y un dock compacto
 * con las favoritas; con DPAD_DOWN sube la grilla con todas las apps sobre el fondo difuminado.
 */
class MainActivity : Activity(), AppAdapter.Callbacks {

    private lateinit var graph: Graph
    private lateinit var repo: AppRepository
    private lateinit var store: FavoritesStore
    private lateinit var loader: ImageLoader
    private lateinit var adapter: AppAdapter
    private lateinit var gridRv: ShelfRecycler
    private lateinit var dockRv: ShelfRecycler
    private lateinit var dockBox: FrameLayout

    private lateinit var content: FrameLayout
    private lateinit var artView: ImageView
    private lateinit var blurView: ImageView
    private lateinit var dimView: View
    private lateinit var gridFade: View
    private lateinit var gridName: android.widget.TextView
    private lateinit var glowL: View
    private lateinit var glowR: View
    private lateinit var topBar: TopBar
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
    private enum class HomeState { DOCK, GRID, TOPBAR, ART_MODE }
    private var state = HomeState.DOCK
    private var stateBeforeArt = HomeState.DOCK
    private val gridOpen get() = state == HomeState.GRID
    private val artMode get() = state == HomeState.ART_MODE
    private var currentArt: Artwork? = null
    private var stopAt = 0L
    private var lastDockSlot = 0
    private var pendingPkg: String? = null
    private var pendingEntry: AppEntry? = null
    private var shelfPkg: String? = null
    private var prevFocus: View? = null
    private var moving = false
    private var focusedOnce = false
    private var warned = false
    private var screenH = 0

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = Graph.from(this)
        repo = AppRepository(this)
        store = FavoritesStore(this)
        screenH = resources.displayMetrics.heightPixels

        val radius = dpf(12f)
        val dockGeo = CellGeo(dp(110), dp(66), dp(5), dp(10), dp(10), false)
        val gridGeo = dockGeo
        val gridTileW = dockGeo.tileW
        val gridTileH = dockGeo.tileH
        loader = ImageLoader(this, gridTileW, gridTileH, radius)
        adapter = AppAdapter(loader, dockGeo, gridGeo, radius, this)

        val root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF181B26.toInt(), 0xFF0A0A10.toInt()),
        )
        window.setBackgroundDrawable(null)
        content = FrameLayout(this)
        root.addView(content, ViewGroup.LayoutParams(-1, -1))

        // Fondo: obra nítida a sangre; versión blur + oscurecido solo con la grilla abierta
        artView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        blurView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        dimView = View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f }
        val scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply {
                setColors(intArrayOf(0x00000000, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.55f, 1f))
            }
        }
        fun glow() = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpf(190f)
                setColors(intArrayOf(0x4D000000, 0x00000000))
            }
        }
        glowL = glow()
        glowR = glow()
        content.addView(artView, ViewGroup.LayoutParams(-1, -1))
        content.addView(blurView, ViewGroup.LayoutParams(-1, -1))
        content.addView(dimView, ViewGroup.LayoutParams(-1, -1))
        content.addView(scrim, ViewGroup.LayoutParams(-1, -1))
        content.addView(glowL, FrameLayout.LayoutParams(dp(380), dp(380)).apply { leftMargin = dp(-40); topMargin = dp(-150) })
        content.addView(glowR, FrameLayout.LayoutParams(dp(380), dp(380), Gravity.END).apply { rightMargin = dp(-100); topMargin = dp(-150) })

        // Grilla de todas las apps: oculta abajo, sube con DPAD_DOWN
        gridRv = ShelfRecycler(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 7)
            adapter = this@MainActivity.adapter.grid
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            setPadding(dp(8), dp(70), dp(8), dp(24))
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.INVISIBLE
            translationY = screenH.toFloat()
        }
        content.addView(gridRv, FrameLayout.LayoutParams(dp(7 * 110 + 7 * 10 + 16), screenH - dp(84), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(84) })
        gridName = tv(this, 34f, Ui.TEXT, "sans-serif-medium").apply {
            maxLines = 1
            setShadowLayer(5f, 0f, 1f, 0xB3000000.toInt())
            visibility = View.INVISIBLE
        }
        content.addView(gridName, FrameLayout.LayoutParams(-2, -2))
        gridFade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
            alpha = 0f
        }
        content.addView(gridFade, FrameLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(84) })

        // Top Shelf (carrusel o nombre) justo encima del dock
        shelfView = TopShelfView(this, loader) { launchItem(it) }
        content.addView(shelfView, FrameLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(293) })

        // Dock compacto abajo, una sola fila centrada, sobre panel translúcido
        dockRv = ShelfRecycler(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.adapter.dock
            setHasFixedSize(false)
            itemAnimator = null
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
        }
        dockBox = FrameLayout(this).apply {
            clipChildren = false
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { setColor(0x47000000); cornerRadius = dpf(14f) }
            addView(dockRv, FrameLayout.LayoutParams(-2, dp(86)))
        }
        content.addView(dockBox, FrameLayout.LayoutParams(-2, dp(86), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(39)
        })

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
        // El foco decide el estado visual: barra superior vs dock
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n ->
            if (n == null) return@addOnGlobalFocusChangeListener
            if (state == HomeState.DOCK && n.isIn(topBar)) applyState(HomeState.TOPBAR)
            else if (state == HomeState.TOPBAR && (n.isIn(dockBox) || n.isIn(shelfView))) applyState(HomeState.DOCK)
        }

        // Permiso de Watch Next / TvProvider: se pide una sola vez
        val prefs = getSharedPreferences("shelf", MODE_PRIVATE)
        if (checkSelfPermission(PERM_TV) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("perm_asked", false)) {
            prefs.edit().putBoolean("perm_asked", true).apply()
            requestPermissions(arrayOf(PERM_TV), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) reloadApps()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (state == HomeState.ART_MODE) applyState(stateBeforeArt, animate = false)
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        reloadApps()
        // La obra rota cada 30 min o al volver tras más de 10 min fuera; si no, se recarga la misma
        val rotate = currentArt == null || (stopAt > 0 && System.currentTimeMillis() - stopAt > AWAY_MS)
        if (rotate) lastArt = System.currentTimeMillis()
        loadArt(rotate)
        main.post(tick)
        scheduleArtMode()
    }

    override fun onStop() {
        started = false
        stopAt = System.currentTimeMillis()
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pkgReceiver) }
        if (today.visibility == View.VISIBLE) closeToday()
        if (artMode) applyState(stateBeforeArt, animate = false)
        releaseArt()
        loader.trim()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // HOME con el launcher ya visible: cerrar overlays y volver al dock
        if (menu.visibility == View.VISIBLE) closeMenu(restore = false)
        if (today.visibility == View.VISIBLE) closeToday()
        endMove()
        if (state != HomeState.DOCK) applyState(HomeState.DOCK)
        focusFirst()
        scheduleArtMode()
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

    private fun warnOnce(what: String, e: Throwable) {
        if (!warned) { warned = true; Log.w("Shelf", "$what: $e") }
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
            if (now - lastArt >= ART_MS) { lastArt = now; loadArt(true) }
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

    private fun loadArt(rotate: Boolean) {
        ioArt.execute {
            var a = if (!rotate) currentArt else null
            if (a == null) a = try { graph.art.next() } catch (_: Throwable) { null }
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
        currentArt = a
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
            reloadApps()
        }
    }

    private fun reloadApps() {
        ioData.execute {
            val apps = repo.load()
            // Watch Next: una sola pasada al iniciar y al cambiar paquetes
            try { graph.shelf.preload(apps.map { it.component.packageName }.distinct()) } catch (e: Throwable) { warnOnce("preload", e) }
            main.post { if (started) { allApps = apps; rebuild() } }
        }
    }

    private fun rebuild(focusSlot: Int = -1) {
        val hidden = store.hidden()
        val visible = allApps.filter { it.component.packageName !in FavoritesStore.ALWAYS_HIDDEN && it.key !in hidden }
        var favKeys = store.favorites()
        if (favKeys == null && allApps.isNotEmpty()) {
            // primer arranque: dock y orden por defecto
            favKeys = store.defaultFavorites(visible)
            store.save(favKeys, store.defaultOrder(visible))
        }
        val idx = HashMap<String, Int>()
        store.order().forEachIndexed { i, k -> idx[k] = i }
        val byKey = visible.associateBy { it.key }
        val favs = favKeys.orEmpty().mapNotNull { byKey[it] }.distinctBy { it.key }
        val favSet = favs.map { it.key }.toSet()
        val grid = visible.filter { it.key !in favSet }.sortedBy { idx[it.key] ?: Int.MAX_VALUE }
        val prev = focusedSlot()
        if (!adapter.submit(favs, grid)) return
        if (!adapter.hasItems()) return
        when {
            focusSlot >= 0 -> focusAt(focusSlot)
            prev != null -> focusAt(prev)
            !focusedOnce -> { focusedOnce = true; focusFirst() }
        }
    }

    /** Slot del tile enfocado (favorita o grilla), o null si el foco está fuera (barra superior, Top Shelf). */
    private fun focusedSlot(): Int? {
        val v = currentFocus ?: return null
        val p = v.parent as? RecyclerView ?: return null
        val i = p.getChildAdapterPosition(v)
        if (i < 0) return null
        return when {
            p === gridRv -> AppAdapter.GRID + i
            p === dockRv -> i
            else -> null
        }
    }

    private fun focusFirst() {
        if (adapter.favCount > 0) focusAt(0) else if (adapter.gridCount > 0) focusAt(AppAdapter.GRID)
    }

    private fun focusAt(slot: Int, tries: Int = 10) {
        if (tries == 10) { Nav.dx = 0; Nav.dy = 0 } // foco programático: sin parallax direccional
        val fav = adapter.isFav(slot)
        val idx = if (fav) slot else slot - AppAdapter.GRID
        if (fav && idx >= adapter.favCount) return
        if (!fav && idx >= adapter.gridCount) return
        if (!fav && !gridOpen) { openGrid(slot); return }
        if (fav && gridOpen) closeGrid(refocus = false)
        val rv = if (fav) dockRv else gridRv
        if (tries == 10) rv.scrollToPosition(idx)
        main.postDelayed({
            val v = rv.findViewHolderForAdapterPosition(idx)?.itemView
            if (v != null) v.requestFocus() else if (tries > 0) focusAt(slot, tries - 1)
        }, 30)
    }

    // ---------- Estado visual (única fuente de verdad) ----------

    private fun View.isIn(group: View): Boolean {
        var v: View? = this
        while (v != null) { if (v === group) return true; v = v.parent as? View }
        return false
    }

    /** Fija TODAS las propiedades visuales del home para el estado pedido, cancelando animaciones previas. */
    private fun applyState(new: HomeState, animate: Boolean = true) {
        val prev = state
        state = new
        val grid = new == HomeState.GRID
        val art = new == HomeState.ART_MODE
        val dur = when {
            grid || prev == HomeState.GRID -> 250L
            art -> 600L
            else -> 200L
        }
        fun set(v: View, a: Float, ty: Float = 0f, endVis: Int = View.VISIBLE) {
            v.animate().cancel()
            if (endVis == View.VISIBLE) v.visibility = View.VISIBLE
            if (!animate) {
                v.alpha = a; v.translationY = ty; v.visibility = endVis
                return
            }
            v.animate().alpha(a).translationY(ty).setDuration(dur).setInterpolator(Ui.DECEL)
                .withEndAction { v.visibility = endVis }.start()
        }
        set(blurView, if (grid) 1f else 0f)
        set(dimView, if (grid) 0.55f else 0f)
        set(gridFade, if (grid) 1f else 0f)
        if (!grid) gridName.visibility = View.INVISIBLE
        if (grid) set(gridRv, 1f, 0f) else set(gridRv, 1f, screenH.toFloat(), View.INVISIBLE)
        when {
            grid -> set(dockBox, 0f, dpf(30f), View.INVISIBLE)
            art -> set(dockBox, 0f)
            else -> set(dockBox, 1f)
        }
        val chrome = if (art) 0f else 1f
        set(topBar, chrome)
        set(glowL, chrome)
        set(glowR, chrome)
        set(shelfView, if (new == HomeState.DOCK) 1f else 0f)
        if (grid) {
            main.removeCallbacks(shelfRun)
            shelfView.clear()
            shelfPkg = null
        }
    }

    private fun openGrid(focusSlot: Int = AppAdapter.GRID) {
        if (gridOpen) return
        applyState(HomeState.GRID)
        focusAt(focusSlot)
    }

    private fun closeGrid(refocus: Boolean = true) {
        if (!gridOpen) return
        applyState(HomeState.DOCK)
        if (refocus) focusAt(lastDockSlot.coerceIn(0, (adapter.favCount - 1).coerceAtLeast(0)))
    }

    // ---------- Callbacks de tiles ----------

    override fun onClick(e: AppEntry) {
        if (moving) return
        try { startActivity(repo.launchIntent(e)) } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLongClick(e: AppEntry, slot: Int) {
        if (moving) return
        openMenu(e, slot)
    }

    override fun onFocus(e: AppEntry, slot: Int) {
        if (!adapter.isFav(slot)) {
            gridName.text = e.label
            main.post {
                val v = (currentFocus as? FocusCell)?.content ?: return@post
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val w = gridName.paint.measureText(e.label)
                val x = loc[0].coerceIn(dp(48), (resources.displayMetrics.widthPixels - w - dp(48)).toInt())
                (gridName.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = x; topMargin = (loc[1] - dp(46)).coerceAtLeast(dp(40)) }
                gridName.requestLayout()
                gridName.visibility = View.VISIBLE
            }
            return
        }
        lastDockSlot = slot
        val pkg = e.component.packageName
        if (pkg != shelfPkg) { shelfView.clear(); shelfPkg = null }
        pendingPkg = pkg
        pendingEntry = e
        main.removeCallbacks(shelfRun)
        main.postDelayed(shelfRun, 300)
    }

    private val shelfRun = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        ioShelf.execute {
            val items = try { graph.shelf.itemsFor(pkg) } catch (e: Throwable) { warnOnce("itemsFor", e); emptyList() }
            main.post {
                if (!started || pendingPkg != pkg || gridOpen) return@post
                shelfPkg = pkg
                if (items.isNotEmpty()) shelfView.show(items) else showAppName()
            }
        }
    }

    /** Nombre de la app sobre su tile enfocado. */
    private fun showAppName() {
        val e = pendingEntry ?: return
        val v = currentFocus as? FocusCell
        var x = dp(65)
        if (v != null && v.parent === dockRv) {
            val loc = IntArray(2)
            v.content.getLocationOnScreen(loc)
            x = loc[0]
        }
        shelfView.showName(e.label, x)
    }

    private fun launchItem(it: TopShelfItem) {
        val uri = it.intentUri ?: return
        try {
            startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el contenido", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Modo arte (sin teclas por 45 s) ----------

    private val artModeRun = Runnable { enterArtMode() }

    private fun scheduleArtMode() {
        main.removeCallbacks(artModeRun)
        main.postDelayed(artModeRun, ART_MODE_MS)
    }

    private fun enterArtMode() {
        if (!started || moving || menu.visibility == View.VISIBLE || today.visibility == View.VISIBLE) return
        if (state != HomeState.DOCK && state != HomeState.TOPBAR) return
        stateBeforeArt = state
        applyState(HomeState.ART_MODE)
    }

    private fun exitArtMode() {
        if (state == HomeState.ART_MODE) applyState(stateBeforeArt)
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

    private fun openMenu(e: AppEntry, slot: Int) {
        prevFocus = currentFocus
        blockContent(true)
        val fav = adapter.isFav(slot)
        menu.show(
            e.label, fav,
            onFav = { closeMenu(restore = false); toggleFav(e, slot, fav) },
            onMove = { closeMenu(); startMove(slot) },
            onHide = { closeMenu(restore = false); hideApp(e, slot) },
            onHidden = { closeMenu(restore = false); openHiddenList() },
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

    private fun toggleFav(e: AppEntry, slot: Int, isFav: Boolean) {
        val favs = adapter.favKeys().toMutableList()
        if (isFav) favs.remove(e.key) else favs.add(e.key)
        store.save(favs, adapter.appKeys())
        val n = favs.size
        rebuildThen {
            when {
                !isFav -> n - 1
                n > 0 -> slot.coerceAtMost(n - 1)
                else -> adapter.slotOf(e.key, false)
            }
        }
    }

    private fun hideApp(e: AppEntry, slot: Int) {
        store.setHidden(store.hidden() + e.key)
        store.save(adapter.favKeys().filter { it != e.key }, adapter.appKeys())
        rebuildThen { if (adapter.isFav(slot)) slot.coerceAtMost(adapter.favCount - 1) else slot.coerceAtMost(AppAdapter.GRID + adapter.gridCount - 1) }
    }

    private fun openHiddenList() {
        prevFocus = currentFocus
        blockContent(true)
        val hid = allApps.filter { it.key in store.hidden() }
        val opts: List<Pair<String, () -> Unit>> = if (hid.isEmpty()) {
            listOf("No hay apps ocultas" to { closeMenu() })
        } else {
            hid.map { a -> "Mostrar ${a.label}" to { closeMenu(restore = false); store.setHidden(store.hidden() - a.key); rebuildThen { if (adapter.favCount > 0) 0 else AppAdapter.GRID } } }
        }
        menu.showList("Apps ocultas", opts)
    }

    private fun rebuildThen(pos: () -> Int) {
        rebuild(focusSlot = -1)
        main.post { focusAt(pos().coerceAtLeast(0)) }
    }

    // ---------- Modo mover ----------

    private fun startMove(slot: Int) {
        val e = adapter.entryAt(slot) ?: return
        moving = true
        adapter.movingKey = e.key
        adapter.movingFav = adapter.isFav(slot)
        adapter.refreshSlot(slot)
        hint.visibility = View.VISIBLE
        focusAt(slot)
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
            adapter.refreshSlot(adapter.slotOf(key, fav))
        }
    }

    private fun moveStep(dx: Int, dy: Int) {
        val from = focusedSlot() ?: return
        val to = if (adapter.isFav(from)) from + dx else from + dx + dy * 7
        if (adapter.move(from, to)) focusAt(to)
    }

    // ---------- Teclas ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val k = e.keyCode
        scheduleArtMode()
        if (state == HomeState.ART_MODE) {
            // cualquier tecla solo despierta la interfaz; no se ejecuta
            if (down) exitArtMode()
            return true
        }
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
            val slot = focusedSlot()
            val en = if (slot != null) adapter.entryAt(slot) else null
            if (en != null) openMenu(en, slot!!)
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down) when (state) {
                HomeState.GRID -> closeGrid()
                HomeState.TOPBAR -> focusAt(lastDockSlot)
                else -> focusFirst()
            }
            return true
        }
        if (down) {
            val slot = focusedSlot()
            // DOWN desde la barra superior vuelve al último tile del dock
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && state == HomeState.TOPBAR && adapter.favCount > 0) {
                focusAt(lastDockSlot.coerceIn(0, adapter.favCount - 1))
                return true
            }
            // DOWN desde el dock sube la grilla; UP desde la primera fila la baja
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && !gridOpen && slot != null && adapter.isFav(slot) && adapter.gridCount > 0) {
                openGrid()
                return true
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && gridOpen && slot != null && slot - AppAdapter.GRID < 7 && adapter.favCount > 0) {
                closeGrid()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Home ya es la raíz")
    override fun onBackPressed() { /* launcher: ignorar back */ }

    private companion object {
        const val ART_MS = 30L * 60_000L
        const val AWAY_MS = 10L * 60_000L
        const val WEATHER_MS = 20L * 60_000L
        const val ART_MODE_MS = 45_000L
        const val PERM_TV = "android.permission.READ_TV_LISTINGS"
    }
}
