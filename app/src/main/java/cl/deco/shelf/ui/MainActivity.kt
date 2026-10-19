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
import android.media.MediaPlayer
import android.os.Debug
import android.view.SurfaceHolder
import android.view.SurfaceView
import cl.deco.shelf.settings.ShelfPrefs
import cl.deco.shelf.util.Palette
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
import cl.deco.shelf.apps.IconOverrides
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
class MainActivity : Activity(), AppAdapter.Callbacks, SettingsHost {

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
    private lateinit var prefs: ShelfPrefs
    private lateinit var settings: SettingsPanel
    private lateinit var settingsPages: SettingsPages
    private lateinit var dockBg: GradientDrawable
    private var artPlayer: MediaPlayer? = null
    private var artSurface: SurfaceView? = null
    private val themeListener: () -> Unit = { applyTheme() }
    private val shelfSettingsEntry = AppEntry("Ajustes de Shelf", android.content.ComponentName(IconOverrides.SHELF_PKG, IconOverrides.SHELF_SETTINGS_CLASS))

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
    private var palette: Palette? = null
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
        prefs = ShelfPrefs(this)
        applyLocalePrefs()
        Theme.dynamic = prefs.dynamicColors
        screenH = resources.displayMetrics.heightPixels

        val radius = dpf(12f)
        // Tile 100x60 dp con 7 dp de margen por lado: gap de 28 px entre tiles (foco 1.06 sin solapes), 8 favoritas caben en 960 dp
        val dockGeo = CellGeo(dp(100), dp(60), dp(7), dp(10), dp(10), false)
        val gridGeo = dockGeo
        loader = ImageLoader(this, TILE_W, TILE_H, radius)
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
            setPadding(dp(8), dp(154), dp(8), dp(24))
            setItemViewCacheSize(14)
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.INVISIBLE
            translationY = screenH.toFloat()
        }
        // Ancho = columnas reales (se ajusta en rebuild) para centrar la grilla con el mismo eje que el dock
        content.addView(gridRv, FrameLayout.LayoutParams(dp(GRID_COLS * CELL_DP + 16), screenH, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        gridName = tv(this, 34f, Ui.TEXT, "sans-serif-medium").apply {
            maxLines = 1
            setShadowLayer(5f, 0f, 1f, 0xB3000000.toInt())
            visibility = View.INVISIBLE
        }
        content.addView(gridName, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        // Degradado suave desde el borde superior (sin escalón): la barra superior y las filas que suben quedan legibles
        gridFade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB0000000.toInt(), 0x55000000, 0x00000000))
            alpha = 0f
        }
        content.addView(gridFade, FrameLayout.LayoutParams(-1, dp(150)))

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
            dockBg = GradientDrawable().apply { setColor(Theme.dockTint()); cornerRadius = dpf(14f) }
            background = dockBg
            addView(dockRv, FrameLayout.LayoutParams(-2, dp(86)))
        }
        content.addView(dockBox, FrameLayout.LayoutParams(-2, dp(86), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(39)
        })

        topBar = TopBar(this) { openToday() }
        content.addView(topBar, FrameLayout.LayoutParams(-1, dp(64)).apply {
            topMargin = dp(18); leftMargin = dp(34); rightMargin = dp(34)
        })

        today = TodayPanel(this).apply { onNeedYear = { loadHolidays(it) }; setCity(prefs.cityName) }
        menu = MenuPanel(this)
        settings = SettingsPanel(this)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        settingsPages = SettingsPages(settings, prefs, this, "Shelf $ver")
        topBar.setWeatherEnabled(prefs.weatherOn)
        Theme.listeners.add(themeListener)
        hint = tv(this, 14f).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dpf(20f) }
            text = "Mueve con las flechas. OK para confirmar"
            visibility = View.GONE
        }
        root.addView(today, ViewGroup.LayoutParams(-1, -1))
        root.addView(menu, ViewGroup.LayoutParams(-1, -1))
        root.addView(settings, ViewGroup.LayoutParams(-1, -1))
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
        if (settings.isOpen) closeSettings()
        if (artMode) applyState(stateBeforeArt, animate = false)
        stopArtVideo(animate = false)
        releaseArt()
        loader.trim()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // HOME con el launcher ya visible: cerrar overlays y volver al dock
        if (menu.visibility == View.VISIBLE) closeMenu(restore = false)
        if (today.visibility == View.VISIBLE) closeToday()
        if (settings.isOpen) closeSettings()
        endMove()
        if (state != HomeState.DOCK) applyState(HomeState.DOCK)
        focusFirst()
        scheduleArtMode()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Shelf ya no está visible: soltar fondo blur y cache de íconos (se recargan en onStart)
            blurView.setImageDrawable(null)
            blurBmp?.recycle(); blurBmp = null
            loader.trim()
        }
    }

    override fun onDestroy() {
        Theme.listeners.remove(themeListener)
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
            topBar.setTime(Es.hora(t.hour, t.minute, prefs.clock24))
            val d = LocalDate.now()
            if (d != lastDate) {
                lastDate = d
                topBar.setDate(Es.fecha(d))
                updateHoliday(d)
            }
            if (now - lastArt >= prefs.bgIntervalMin * 60_000L) { lastArt = now; loadArt(true) }
            if (prefs.weatherOn && now - lastWeather >= WEATHER_MS) { lastWeather = now; refreshWeather() }
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
            var pal: Palette? = null
            if (a != null) {
                bmp = try { BitmapUtil.decodeFile(a.file.path, 1920, 1080) } catch (_: Throwable) { null }
                if (bmp != null) {
                    blur = makeBlur(bmp)
                    pal = try { Palette.from(bmp) } catch (_: Throwable) { null }
                }
            }
            val art = a
            main.post {
                if (!started || art == null || bmp == null) { bmp?.recycle(); blur?.recycle() } else applyArt(art, bmp, blur, pal)
            }
            // repone cache para las próximas rotaciones
            if (art != null) try { graph.art.prefetch(2) } catch (_: Throwable) {}
        }
    }

    /** Blur precalculado una vez: 960x540 y reducción interna 1/8; el radio sale del ajuste "Desenfoque en grilla". */
    private fun makeBlur(bmp: Bitmap): Bitmap? = try {
        val small = Bitmap.createScaledBitmap(bmp, 960, 540, true)
        val b = Blur.blurred(small, BLUR_RADII[prefs.blurLevel])
        if (b !== small) small.recycle()
        b
    } catch (_: Throwable) { null }

    /** Cambió el nivel de desenfoque: recalcula solo el blur a partir de la obra actual. */
    private fun rebuildBlur() {
        val src = artBmp ?: return
        ioArt.execute {
            val b = try { if (src.isRecycled) null else makeBlur(src) } catch (_: Throwable) { null }
            main.post {
                if (b == null || !started) { b?.recycle(); return@post }
                val old = blurBmp
                blurBmp = b
                blurView.setImageBitmap(b)
                if (old != null) main.postDelayed({ old.recycle() }, 400)
            }
        }
    }

    private fun applyArt(a: Artwork, bmp: Bitmap, blur: Bitmap?, pal: Palette? = null) {
        currentArt = a
        palette = pal
        applyPalette(animate = artBmp != null)
        val old = artBmp
        val newD = BitmapDrawable(resources, bmp)
        artBmp = bmp
        artId = a.id
        if (old == null) {
            artView.setImageDrawable(newD)
            if (artPlayer == null) {
                artView.alpha = 0f
                artView.animate().alpha(1f).setDuration(350).start()
            }
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

    /** Paleta dinámica de la obra o neutra, según el ajuste. */
    private fun applyPalette(animate: Boolean) {
        val p = if (prefs.dynamicColors) palette else null
        Theme.animateTo(p ?: Theme.neutral(), 400L, animate)
    }

    private fun applyTheme() {
        dockBg.setColor(Theme.dockTint())
        topBar.setAccent(Theme.accent, Theme.light)
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
            // Una app cambió: su tile cacheado se borra y se regenera con el arte nuevo
            val pkg = i?.data?.schemeSpecificPart
            if (pkg != null && pkg != packageName) loader.invalidate(pkg)
            reloadApps()
            if (pkg != null) main.postDelayed({ adapter.refreshPkg(pkg) }, 600)
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
        val names = store.names()
        val visible = allApps.filter { it.component.packageName !in FavoritesStore.ALWAYS_HIDDEN && it.key !in hidden }
            .map { e -> names[e.component.packageName]?.let { e.copy(label = it) } ?: e }
        var favKeys = store.favorites()
        if (favKeys == null && allApps.isNotEmpty()) {
            // primer arranque: dock y orden por defecto
            favKeys = store.defaultFavorites(visible)
            store.save(favKeys, store.defaultOrder(visible))
        }
        val idx = HashMap<String, Int>()
        store.order().forEachIndexed { i, k -> idx[k] = i }
        val byKey = visible.associateBy { it.key }
        // Máximo de favoritas del ajuste: las que sobran se muestran en la grilla
        val favs = favKeys.orEmpty().mapNotNull { byKey[it] }.distinctBy { it.key }.take(prefs.dockMax)
        val favSet = favs.map { it.key }.toSet()
        val grid = visible.filter { it.key !in favSet }.sortedBy { idx[it.key] ?: Int.MAX_VALUE } + shelfSettingsEntry
        layoutGrid(grid.size)
        val prev = focusedSlot()
        if (!adapter.submit(favs, grid)) return
        if (!adapter.hasItems()) return
        when {
            focusSlot >= 0 -> focusAt(focusSlot)
            prev != null -> focusAt(prev)
            !focusedOnce -> { focusedOnce = true; focusFirst() }
        }
    }

    /** Columnas = min(7, apps) y ancho a medida, para centrar la grilla en el mismo eje que el dock. */
    private fun layoutGrid(count: Int) {
        val cols = count.coerceIn(1, GRID_COLS)
        (gridRv.layoutManager as GridLayoutManager).spanCount = cols
        val lp = gridRv.layoutParams as FrameLayout.LayoutParams
        val w = dp(cols * CELL_DP + 16)
        if (lp.width != w) { lp.width = w; gridRv.layoutParams = lp }
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
        if (prev == HomeState.ART_MODE && new != HomeState.ART_MODE) {
            main.removeCallbacks(artVideoRun)
            stopArtVideo(animate)
        }
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
        // Brillo del fondo: scrim negro; la grilla suma el oscurecimiento fijo bajo el blur
        val bright = prefs.bgBrightness / 100f
        set(dimView, if (grid) 1f - bright * 0.45f else 1f - bright)
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
        if (art) main.postDelayed(artVideoRun, ART_VIDEO_DELAY_MS)
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
        if (e.component.className == IconOverrides.SHELF_SETTINGS_CLASS) { openSettings(); return }
        try { startActivity(repo.launchIntent(e)) } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLongClick(e: AppEntry, slot: Int) {
        if (moving) return
        if (e.component.className == IconOverrides.SHELF_SETTINGS_CLASS) { openSettings(); return }
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
                // translation en vez de márgenes: sin requestLayout al mover el foco
                gridName.translationX = loc[0].coerceIn(dp(48), (resources.displayMetrics.widthPixels - w - dp(48)).toInt()).toFloat()
                gridName.translationY = (loc[1] - dp(46)).coerceAtLeast(dp(40)).toFloat()
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
            val items = try { if (prefs.topShelf) graph.shelf.itemsFor(pkg) else emptyList() } catch (e: Throwable) { warnOnce("itemsFor", e); emptyList() }
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
        if (prefs.artEnabled) main.postDelayed(artModeRun, prefs.artWaitSec * 1000L)
    }

    private val artVideoRun = Runnable { startArtVideo() }

    private fun videoFile(): File? =
        getExternalFilesDir("video")?.listFiles { f -> f.extension.equals("mp4", true) }?.minByOrNull { it.name }

    /**
     * Video detrás del fondo: SurfaceView en el índice 0 de content (la obra queda encima) y MediaPlayer en loop sin audio.
     * Al primer frame real se funde la obra de alpha 1 a 0 en 1200 ms. Sin video o con el ajuste apagado, el modo arte
     * queda como siempre.
     */
    private fun startArtVideo() {
        if (state != HomeState.ART_MODE || artPlayer != null || !started || !prefs.artVideo) return
        val file = videoFile() ?: return
        val sv = SurfaceView(this)
        artSurface = sv
        content.addView(sv, 0, ViewGroup.LayoutParams(-1, -1))
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (artSurface !== sv || artPlayer != null) return
                val mp = MediaPlayer()
                artPlayer = mp
                try {
                    mp.setDataSource(file.path)
                    mp.setSurface(h.surface)
                    mp.setVolume(0f, 0f)
                    mp.isLooping = true
                    mp.setOnPreparedListener { it.start(); main.postDelayed(fadeArtOut, 1500) }
                    mp.setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) fadeArtOut.run()
                        false
                    }
                    mp.setOnErrorListener { _, w, x -> Log.w("Shelf", "video modo arte: $w/$x"); stopArtVideo(true); true }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.w("Shelf", "video modo arte: $e")
                    stopArtVideo(true)
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private val fadeArtOut: Runnable = object : Runnable { override fun run() {
        main.removeCallbacks(fadeArtOut)
        if (artPlayer != null && state == HomeState.ART_MODE) artView.animate().alpha(0f).setDuration(1200).start()
    } }

    /** Vuelve la obra a alpha 1 (200 ms) y recién entonces detiene, libera y quita el SurfaceView. */
    private fun stopArtVideo(animate: Boolean) {
        main.removeCallbacks(fadeArtOut)
        val mp = artPlayer; val sv = artSurface
        artPlayer = null; artSurface = null
        if (mp == null && sv == null) return
        artView.animate().cancel()
        val free = Runnable {
            if (mp != null) { runCatching { mp.stop() }; runCatching { mp.release() } }
            if (sv != null) runCatching { content.removeView(sv) }
        }
        if (animate && started) artView.animate().alpha(1f).setDuration(200).withEndAction(free).start()
        else { artView.alpha = 1f; free.run() }
    }

    private fun enterArtMode() {
        if (!started || !prefs.artEnabled || moving || menu.visibility == View.VISIBLE || today.visibility == View.VISIBLE || settings.isOpen) return
        if (state != HomeState.DOCK && state != HomeState.TOPBAR) return
        stateBeforeArt = state
        applyState(HomeState.ART_MODE)
    }

    private fun exitArtMode() {
        if (state == HomeState.ART_MODE) applyState(stateBeforeArt)
    }

    // ---------- Overlays ----------

    // ---------- Ajustes de Shelf ----------

    private fun openSettings() {
        prevFocus = currentFocus
        blockContent(true)
        settings.open(settingsPages.root())
    }

    private fun closeSettings() {
        settings.close()
        blockContent(false)
        prevFocus?.requestFocus()
    }

    /** Renombrar una app: página de ajustes con EditText (teclado del sistema); el nombre persiste por paquete. */
    private fun openRename(e: AppEntry) {
        prevFocus = currentFocus
        blockContent(true)
        val pkg = e.component.packageName
        val orig = allApps.firstOrNull { it.component.packageName == pkg }?.label ?: e.label
        fun done(name: String?) { store.setName(pkg, name); closeSettings(); rebuild() }
        settings.open(Page("Renombrar ${orig}", listOf(
            Row.Input("Nuevo nombre", e.label, false) { done(it.ifBlank { null }) },
            Row.Act("Restaurar nombre original", { orig }) { done(null) },
        )))
    }

    override fun changed(key: String) {
        when (key) {
            ShelfPrefs.BG_ONLINE -> { lastArt = System.currentTimeMillis(); loadArt(true) }
            ShelfPrefs.BG_BRIGHTNESS -> applyState(state, animate = false)
            ShelfPrefs.BLUR_LEVEL -> rebuildBlur()
            ShelfPrefs.DYNAMIC_COLORS -> { Theme.dynamic = prefs.dynamicColors; applyPalette(animate = true) }
            ShelfPrefs.ART_ON, ShelfPrefs.ART_WAIT -> scheduleArtMode()
            ShelfPrefs.TOP_SHELF -> { shelfView.clear(); shelfPkg = null; main.post(shelfRun) }
            ShelfPrefs.CLOCK24 -> { main.removeCallbacks(tick); main.post(tick) }
            ShelfPrefs.WEATHER_ON -> {
                topBar.setWeatherEnabled(prefs.weatherOn)
                if (prefs.weatherOn) { lastWeather = System.currentTimeMillis(); refreshWeather() }
            }
            ShelfPrefs.DOCK_MAX -> rebuild()
            ShelfPrefs.ICON_ORIGINAL -> regenerateIcons()
            ShelfPrefs.CITY_NAME -> {
                today.setCity(prefs.cityName)
                weather = null; topBar.setWeather(null); today.setWeather(null)
                lastWeather = System.currentTimeMillis(); refreshWeather()
            }
            ShelfPrefs.HOL_CC, ShelfPrefs.HOL_REGION -> {
                holidayCache.clear(); holidayLoading.clear(); today.clearHolidays()
                lastDate = null; holidayName = null; topBar.setHoliday(null)
                main.removeCallbacks(tick); main.post(tick)
            }
            ShelfPrefs.FAHRENHEIT, ShelfPrefs.ENGLISH, ShelfPrefs.SUNDAY_FIRST -> {
                applyLocalePrefs()
                topBar.setWeather(weather)
                lastDate = null
                main.removeCallbacks(tick); main.post(tick)
                today.refresh()
            }
        }
    }

    private fun applyLocalePrefs() {
        Es.english = prefs.english
        Es.sundayFirst = prefs.sundayFirst
        Units.fahrenheit = prefs.fahrenheit
    }

    override fun hiddenCount() = store.hidden().size

    override fun restoreHidden() {
        store.setHidden(emptySet())
        rebuild()
    }

    override fun resetOrder() {
        store.resetLayout()
        rebuild()
    }

    override fun regenerateIcons() {
        loader.regenerateAll()
        adapter.refreshAll()
    }

    override fun pss(): String = "%.1f MB".format(Debug.getPss() / 1024.0)

    override fun exportBackup(): String {
        val dir = getExternalFilesDir("backup") ?: return "Sin almacenamiento disponible"
        dir.mkdirs()
        val all = JSONObject()
        for ((k, v) in prefs.sp.all) {
            val o = JSONObject()
            when (v) {
                is String -> o.put("t", "s").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Boolean -> o.put("t", "b").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            all.put(k, o)
        }
        val root = JSONObject().put("app", packageName).put("format", 1).put("prefs", all)
        val f = File(dir, "shelf-backup-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".json")
        return try {
            f.writeText(root.toString(2))
            "Guardado: /sdcard/Android/data/$packageName/files/backup/${f.name} (respaldar con adb pull)"
        } catch (e: Exception) { "Error al exportar: ${e.message}" }
    }

    override fun backups(): List<File> =
        getExternalFilesDir("backup")?.listFiles { f -> f.name.startsWith("shelf-backup-") && f.extension == "json" }
            ?.sortedByDescending { it.name }.orEmpty()

    override fun restoreBackup(f: File) {
        try {
            val all = JSONObject(f.readText()).getJSONObject("prefs")
            val ed = prefs.sp.edit().clear()
            for (k in all.keys()) {
                val o = all.getJSONObject(k)
                when (o.getString("t")) {
                    "s" -> ed.putString(k, o.getString("v"))
                    "i" -> ed.putInt(k, o.getInt("v"))
                    "l" -> ed.putLong(k, o.getLong("v"))
                    "b" -> ed.putBoolean(k, o.getBoolean("v"))
                    "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                    "ss" -> { val a = o.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            ed.commit()
            recreate() // relee todo desde las prefs restauradas
        } catch (e: Exception) {
            Toast.makeText(this, "Respaldo inválido: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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
            onRename = { closeMenu(restore = false); openRename(e) },
            onSettings = { closeMenu(restore = false); openSettings() },
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
        if (!isFav && adapter.favCount >= prefs.dockMax) {
            Toast.makeText(this, "Dock lleno (máximo ${prefs.dockMax})", Toast.LENGTH_SHORT).show()
            return
        }
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
        val to = if (adapter.isFav(from)) from + dx else from + dx + dy * GRID_COLS
        if (adapter.entryAt(to)?.component?.className == IconOverrides.SHELF_SETTINGS_CLASS) return // el tile de Shelf queda al final
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
        if (settings.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down && !settings.back()) closeSettings(); return true }
            return super.dispatchKeyEvent(e)
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
            if (en != null && en.component.className != IconOverrides.SHELF_SETTINGS_CLASS) openMenu(en, slot!!) else openSettings()
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
        const val TILE_W = 220
        const val TILE_H = 132
        const val GRID_COLS = 7
        const val CELL_DP = 114 // 100 dp de tile + 2 x 7 dp (gap de 28 px)
        val BLUR_RADII = intArrayOf(10, 20, 32)
        const val ART_VIDEO_DELAY_MS = 45_000L
        const val AWAY_MS = 10L * 60_000L
        const val WEATHER_MS = 20L * 60_000L
        const val PERM_TV = "android.permission.READ_TV_LISTINGS"
    }
}
