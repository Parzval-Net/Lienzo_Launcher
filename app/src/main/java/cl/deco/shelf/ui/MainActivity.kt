package cl.deco.shelf.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Debug
import cl.deco.shelf.settings.ShelfPrefs
import cl.deco.shelf.clock.AlarmService
import cl.deco.shelf.clock.ClockScheduler
import cl.deco.shelf.clock.ClockStore
import cl.deco.shelf.clock.NightMode
import cl.deco.shelf.util.Seasonal
import java.io.File
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.BuildConfig
import cl.deco.shelf.Graph
import cl.deco.shelf.guard.CrashLog
import cl.deco.shelf.guard.SafeActivity
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.AppRepository
import cl.deco.shelf.apps.FavoritesStore
import cl.deco.shelf.apps.IconOverrides
import cl.deco.shelf.apps.ImageLoader
import cl.deco.shelf.data.art.MediaImporter
import cl.deco.shelf.data.holidays.Holiday
import cl.deco.shelf.data.weather.Weather
import cl.deco.shelf.shelf.TopShelfItem
import cl.deco.shelf.util.NetworkWatcher
import cl.deco.shelf.util.HealthNotice
import cl.deco.shelf.util.HealthNoticeStore
import cl.deco.shelf.util.MemoryCleaner
import cl.deco.shelf.update.UpdateChecker
import cl.deco.shelf.update.UpdateInfo
import cl.deco.shelf.update.UpdatePanel
import cl.deco.shelf.update.UpdateRules
import cl.deco.shelf.update.UpdateStore
import cl.deco.shelf.data.art.ArtProgress
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

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
    private lateinit var gridFade: View
    private lateinit var gridName: android.widget.TextView
    private lateinit var gridHeader: android.widget.TextView
    private lateinit var gridScrim: View
    private lateinit var contRv: ContinueRow
    private lateinit var contHeader: android.widget.TextView
    private var contHas = false
    private lateinit var scrimBg: GradientDrawable
    private var tileDp = 112
    private var cellDp = 124
    private lateinit var topBar: TopBar
    private lateinit var shelfView: TopShelfView
    private lateinit var today: TodayPanel
    private lateinit var menu: MenuPanel
    private lateinit var inputsPopup: InputsPopup
    private lateinit var hdmiNotice: HdmiNotice
    private lateinit var hint: android.widget.TextView
    private lateinit var prefs: ShelfPrefs
    private lateinit var settings: SettingsPanel
    private lateinit var health: HealthPanel
    private lateinit var importPanel: ImportPanel
    private lateinit var lanPanel: LanTransferPanel
    private lateinit var updatePanel: UpdatePanel
    private var pendingImport = false
    private lateinit var settingsPages: SettingsPages
    private lateinit var dockBg: GradientDrawable
    private lateinit var artController: ArtController
    private var netWatcher: NetworkWatcher? = null
    private var artPolling = false
    private val artProgressTick = Runnable { artPolling = false; pollArtProgress() }
    private val secTick: Runnable = object : Runnable { override fun run() { updateClockStatus() } }
    private val themeListener: () -> Unit = { applyTheme() }
    private val shelfSettingsEntry = AppEntry("Ajustes de Lienzo", android.content.ComponentName(IconOverrides.SHELF_PKG, IconOverrides.SHELF_SETTINGS_CLASS))

    private val main = Handler(Looper.getMainLooper())
    private val ioData = newExecutor()
    private val ioShelf = newExecutor()
    private val ioArt = newExecutor()

    private var started = false
    /** true cuando onCreate terminó de armar la UI; antes (modo seguro, finish() temprano o una excepción) ningún callback toca las vistas. */
    private var ready = false
    @Volatile private var appsReloading = false
    @Volatile private var appsReloadPending = false
    private var allApps: List<AppEntry> = emptyList()
    private var weather: Weather? = null
    private var lastWeather = 0L
    private var lastDate: LocalDate? = null
    private var holidayName: String? = null
    private val holidayCache = HashMap<Int, List<Holiday>>()
    private val holidayLoading = HashSet<Int>()

    private lateinit var rootView: FrameLayout
    private enum class HomeState { DOCK, GRID, TOPBAR, ART_MODE }
    private var state = HomeState.DOCK
    private var stateBeforeArt = HomeState.DOCK
    private val gridOpen get() = state == HomeState.GRID
    private val artMode get() = state == HomeState.ART_MODE
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
        // Modo seguro: 3 crashes en 5 min. Se cede el home a una Activity mínima en el proceso :guard
        val tg = android.os.SystemClock.elapsedRealtime()
        CrashLog.absorbExits(this)
        Log.i("Shelf", "guard: ${android.os.SystemClock.elapsedRealtime() - tg} ms")
        if (CrashLog.safeMode(this)) { SafeActivity.launch(this); finish(); return }
        graph = Graph.from(this)
        repo = AppRepository(this)
        store = FavoritesStore(this)
        prefs = ShelfPrefs(this)
        applyLocalePrefs()
        Theme.dynamic = prefs.dynamicColors
        Theme.season = Seasonal.paletteFor(LocalDate.now(), prefs.seasonal)
        screenH = resources.displayMetrics.heightPixels

        val radius = dpf(12f)
        // Tiles 16:9 (formato nativo de los banners). 224x126 px con gap de 24 px: 7 tiles (el máximo del dock) ocupan 1712 px
        tileDp = 112
        cellDp = tileDp + 12
        val tileH = tileDp * 9 / 16
        val dockGeo = CellGeo(dp(tileDp), dp(tileH), dp(6), dp(10), dp(10), false)
        // En la grilla el nombre va bajo el tile enfocado: más aire inferior entre filas
        val gridGeo = CellGeo(dp(tileDp), dp(tileH), dp(6), dp(10), dp(26), false)
        loader = ImageLoader(this, TILE_W, TILE_H, radius)
        adapter = AppAdapter(loader, dockGeo, gridGeo, radius, this)

        val root = FrameLayout(this)
        // Sin obra aún (primer inicio sin descargas): degradado de la paleta neutra, nunca negro; cede el paso a la primera obra con crossfade
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF232B40.toInt(), 0xFF141824.toInt(), 0xFF0B0D14.toInt()))
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = screenH * 1.1f
            setGradientCenter(0.5f, 1.05f)
            colors = intArrayOf(Theme.withAlpha(Theme.NEUTRAL_ACCENT, 0x2E), Theme.withAlpha(Theme.NEUTRAL_ACCENT, 0))
        }
        root.background = android.graphics.drawable.LayerDrawable(arrayOf(base, glow))
        window.setBackgroundDrawable(null)
        rootView = root
        content = FrameLayout(this)
        root.addView(content, ViewGroup.LayoutParams(-1, -1))

        // Fondo: obra nítida a sangre; versión blur + oscurecido solo con la grilla abierta
        artView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        blurView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        content.addView(artView, ViewGroup.LayoutParams(-1, -1))
        content.addView(blurView, ViewGroup.LayoutParams(-1, -1))
        artController = ArtController(
            this, main, ioArt, ioShelf, graph, prefs, loader,
            rootView, content, artView, blurView, screenH,
            object : ArtController.Callbacks {
                override fun isStarted() = started
                override fun isGrid() = state == HomeState.GRID
                override fun isArtMode() = artMode
                override fun weather() = weather
                override fun onEnterArtMode() {
                    if (state != HomeState.DOCK && state != HomeState.TOPBAR) applyState(HomeState.DOCK, animate = false)
                    stateBeforeArt = HomeState.DOCK
                    applyState(HomeState.ART_MODE)
                }
                override fun onExitArtMode() {
                    if (artMode) applyState(stateBeforeArt, animate = false)
                }
                override fun onThemeDirty() = applyTheme()
                override fun onPollArtProgress() = pollArtProgress()
            },
        )

        // Grilla de todas las apps: oculta abajo, sube con DPAD_DOWN
        gridRv = ShelfRecycler(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 7)
            adapter = this@MainActivity.adapter.grid
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = true // las filas que suben se recortan bajo el encabezado, no lo pisan
            clipChildren = false
            setPadding(dp(8), dp(160), dp(8), dp(24))
            setItemViewCacheSize(14)
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.INVISIBLE
            translationY = screenH.toFloat()
        }
        // Ancho = columnas reales (se ajusta en rebuild) para centrar la grilla con el mismo eje que el dock
        content.addView(gridRv, FrameLayout.LayoutParams(dp(GRID_COLS * cellDp + 16), screenH, Gravity.TOP or Gravity.START))
        // Fila "Continuar viendo" (Watch Next de todas las apps), sobre "Todas las apps"
        contRv = ContinueRow(this, loader, { pkg -> allApps.firstOrNull { it.component.packageName == pkg } }) { launchItem(it) }
        // Ancho completo: sangra hasta el borde derecho; el margen seguro (96 px) va como padding en la propia fila
        content.addView(contRv, FrameLayout.LayoutParams(-1, dp(146), Gravity.TOP).apply { topMargin = dp(126) })
        contHeader = tv(this, 20f, Ui.TEXT, "sans-serif-medium").apply {
            text = t("Continuar viendo", "Continue watching")
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            alpha = 0f; visibility = View.INVISIBLE
        }
        content.addView(contHeader, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(100) })
        // Scrim detrás de "Continuar viendo" y la grilla: 0% en y=150 px a 40%, constante hacia abajo
        scrimBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x66000000, 0x66000000))
        gridScrim = View(this).apply { background = scrimBg; alpha = 0f; visibility = View.INVISIBLE }
        content.addView(gridScrim, FrameLayout.LayoutParams(-1, screenH - 150).apply { topMargin = 150 })
        // Nombre de la app enfocada, bajo su tile (como tvOS)
        gridName = tv(this, 18f, Ui.TEXT, "sans-serif-medium").apply {
            maxLines = 1
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            visibility = View.INVISIBLE
        }
        gridHeader = tv(this, 20f, Ui.TEXT, "sans-serif-medium").apply {
            text = t("Todas las apps", "All apps")
            setShadowLayer(8f, 0f, 1f, 0x99000000.toInt())
            alpha = 0f
            visibility = View.INVISIBLE
        }
        // Posición por márgenes (applyState anima translationY de las vistas que controla)
        content.addView(gridHeader, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(138) })
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

        topBar = TopBar(
            this,
            onClock = { openClock() },
            onWeather = { openWeatherPanel() },
            onInputs = { openInputs() },
            onWifi = { QuickAccess.launch(this, QuickAccess.items[0]) },
            onSettings = { openSettingsChooser() },
        )
        topBar.setSeason(Theme.season != null)
        content.addView(topBar, FrameLayout.LayoutParams(-1, dp(76)).apply {
            topMargin = dp(16); leftMargin = dp(34); rightMargin = dp(34)
        })

        // Orden de dibujo: el dock atenuado queda debajo de la grilla; la barra superior encima de todo
        gridRv.bringToFront(); contRv.bringToFront(); contHeader.bringToFront(); gridHeader.bringToFront(); gridName.bringToFront(); gridFade.bringToFront(); topBar.bringToFront()
        today = TodayPanel(this).apply { onNeedYear = { loadHolidays(it) }; setCity(prefs.cityName); onClockChanged = { updateClockStatus() } }
        menu = MenuPanel(this)
        inputsPopup = InputsPopup(this).apply {
            onClose = { closeInputs() }
            onPick = { s ->
                closeInputs()
                releaseVideoForPlayback()
                if (!TvInputs.launch(this@MainActivity, s)) Toast.makeText(this@MainActivity, t("No se pudo abrir ${s.label}", "Could not open ${s.label}"), Toast.LENGTH_SHORT).show()
            }
        }
        hdmiNotice = HdmiNotice(this)
        settings = SettingsPanel(this)
        health = HealthPanel(this).apply {
            snapshot = { healthSnapshot() }
            onTrimCaches = { trimCaches() }
            onSnooze = { dismissHealthNotice(); closeHealth() }
            onOpenAndroidSettings = { QuickAccess.launch(this@MainActivity, QuickAccess.items.last()) }
        }
        updatePanel = UpdatePanel(this).apply {
            onSnooze = { dismissUpdateNotice(); closeUpdate() }
            onClose = { closeUpdate() }
        }
        importPanel = ImportPanel(this).apply {
            onClose = { closeImport() }
            onImported = { fondosChanged(); settingsPages.refreshFondos() }
        }
        lanPanel = LanTransferPanel(this).apply {
            onClose = { closeLanTransfer() }
            onMediaImported = { fondosChanged(); settingsPages.refreshFondos() }
            onBackupRestored = {
                dropLanTransfer()
                if (settings.isOpen) settings.close()
                recreate()
            }
        }
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        settingsPages = SettingsPages(this, settings, prefs, this, "Lienzo $ver")
        topBar.setWeatherEnabled(prefs.weatherOn)
        netWatcher = NetworkWatcher(this, main, { onNetChanged(it) }, { topBar.setVpn(it) })
        Theme.listeners.add(themeListener)
        hint = tv(this, 14f).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dpf(20f) }
            text = t("Mueve con las flechas. OK para confirmar", "Use arrows to move. OK to confirm")
            visibility = View.GONE
        }
        root.addView(today, ViewGroup.LayoutParams(-1, -1))
        root.addView(menu, ViewGroup.LayoutParams(-1, -1))
        root.addView(inputsPopup, ViewGroup.LayoutParams(-1, -1))
        root.addView(hdmiNotice, ViewGroup.LayoutParams(-1, -1))
        root.addView(settings, ViewGroup.LayoutParams(-1, -1))
        root.addView(health, ViewGroup.LayoutParams(-1, -1))
        root.addView(importPanel, ViewGroup.LayoutParams(-1, -1))
        root.addView(lanPanel, ViewGroup.LayoutParams(-1, -1))
        root.addView(updatePanel, ViewGroup.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        setContentView(root)
        // El foco decide el estado visual: barra superior vs dock
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n ->
            if (n == null) return@addOnGlobalFocusChangeListener
            if (n.isIn(contRv)) gridName.visibility = View.INVISIBLE // el nombre de app solo acompaña a la grilla
            if (state == HomeState.DOCK && n.isIn(topBar)) applyState(HomeState.TOPBAR)
            else if (state == HomeState.TOPBAR && (n.isIn(dockBox) || n.isIn(shelfView))) applyState(HomeState.DOCK)
        }

        // Permiso de Watch Next / TvProvider: se pide una sola vez
        val prefs = getSharedPreferences("shelf", MODE_PRIVATE)
        if (checkSelfPermission(PERM_TV) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("perm_asked", false)) {
            prefs.edit().putBoolean("perm_asked", true).apply()
            requestPermissions(arrayOf(PERM_TV), 1)
        }
        ready = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!ready) return
        if (requestCode == REQ_MEDIA) {
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val wanted = pendingImport
            pendingImport = false
            if (wanted && ok) openImport()
            else if (wanted) Toast.makeText(this, t("Sin el permiso de medios Lienzo no puede leer el pendrive", "Without media permission, Lienzo cannot read USB storage"), Toast.LENGTH_LONG).show()
            return
        }
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) reloadApps()
        if (settings.isOpen) settings.rebuild()
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        started = true
        startedAt = android.os.SystemClock.uptimeMillis()
        if (state == HomeState.ART_MODE) applyState(stateBeforeArt, animate = false)
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        registerReceiver(screenWakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        reloadApps()
        // Red: el callback vive solo mientras el home está visible (se retira en onStop)
        if (BuildConfig.FORCE_OFFLINE) { topBar.setOnline(false); topBar.setVpn(false) }
        else netWatcher?.let { it.start(); topBar.setOnline(it.online); topBar.setVpn(it.vpn) }
        cl.deco.shelf.clock.SleepTimer.restoreTimeoutIfNeeded(this)
        ioData.execute { cl.deco.shelf.service.ServiceRepair.repairIfNeeded(applicationContext) }
        scheduleOpenOnStart()
        pollArtProgress()
        // La obra rota cada 30 min o al volver tras más de 10 min fuera; si no, se recarga la misma
        checkLowSpace()
        // Salud del deco: se mide unos segundos después de volver, cuando la app anterior ya soltó su memoria de video
        main.postDelayed(checkHealth, 4000)
        // Actualizaciones: al volver al home, como máximo cada 24 h, después de la medición de salud
        main.postDelayed(checkUpdate, 6000)
        updatePanel.onHostStart()
        // Asistente de primer inicio: una sola vez; después, desde Ajustes
        if (!prefs.setupDone) { prefs.setupDone = true; main.postDelayed({ if (started) openWizard() }, 900) }
        updateClockStatus() // alarmas y apagado nocturno ya se reprograman en App.onCreate, BootReceiver y al cambiar ajustes
        artController.onStart(stopAt)
        try { tvInputs?.registerCallback(inputsCallback, main) } catch (_: Throwable) {}
        // Consultas IPC de Wi-Fi, servicio de botones y entradas fuera del primer cuadro (evita bloqueo en SoC RTD2851M)
        ioData.execute {
            val ssid = getWifiSsid()
            val keySvc = cl.deco.shelf.service.KeyMap.serviceRunning(this)
            val has = try { TvInputs.sources(this).isNotEmpty() || TvInputs.systemPanel(this) != null } catch (_: Throwable) { true }
            main.post {
                if (!started) return@post
                topBar.setWifiSsid(ssid)
                topBar.setInputsAvailable(has)
                if (!keySvc && homeHdmiWatcher == null) homeHdmiWatcher = cl.deco.shelf.service.HdmiWatcher(this).also { it.start() }
            }
        }
        main.post(tick)
        scheduleArtMode()
        if (handleRemoteAction(intent, 400L)) intent.action = Intent.ACTION_MAIN // arranque en frío: se consume una sola vez
    }

    /** Intents de KeyService (botones Entradas y Ajustes del control). */
    private fun handleRemoteAction(i: Intent?, delay: Long): Boolean = when (i?.action) {
        ACTION_OPEN_INPUTS -> { main.postDelayed({ if (started) openInputs() }, delay); true }
        cl.deco.shelf.service.KeyMap.ACTION_OPEN_APPS -> {
            main.postDelayed({ if (started && !gridOpen && adapter.gridCount > 0) openGrid() }, delay)
            true
        }
        cl.deco.shelf.service.KeyMap.ACTION_ART_MODE -> {
            val video = i.getBooleanExtra(cl.deco.shelf.service.KeyMap.EXTRA_ART_VIDEO, false)
            // Pedido explícito con un botón: entra aunque el modo arte automático esté desactivado en Ajustes
            main.postDelayed({ if (started) artController.artButton(video) }, delay)
            true
        }
        cl.deco.shelf.service.KeyMap.ACTION_INPUT_CONNECTED -> {
            val id = i.getStringExtra(cl.deco.shelf.service.KeyMap.EXTRA_INPUT_ID)
            if (id != null) main.postDelayed({ if (started) showInputConnected(id) }, delay)
            id != null
        }
        ACTION_OPEN_SETTINGS -> {
            main.postDelayed({
                if (!started || settings.isOpen || menu.visibility == View.VISIBLE) return@postDelayed
                if (state != HomeState.DOCK) applyState(HomeState.DOCK, animate = false)
                focusFirst()
                main.postDelayed({ if (started) openSettingsChooser() }, 60) // tras fijar el foco en el dock
            }, delay)
            true
        }
        else -> false
    }

    /**
     * Aviso al enchufar un equipo en un HDMI: "Un dispositivo se conectó en HDMI 3" (o el nombre CEC del equipo) con la
     * opción de cambiar a esa entrada. Lo pide HdmiWatcher desde el servicio de botones (cualquier app en pantalla) o, sin
     * servicio, el vigía propio del home.
     */
    private fun showInputConnected(inputId: String) {
        ioShelf.execute {
            val s = try { TvInputs.hdmiSource(this, inputId) } catch (_: Throwable) { null } ?: return@execute
            main.post {
                if (!started || settings.isOpen || inputsPopup.isOpen) return@post
                if (menu.visibility == View.VISIBLE) closeMenu(restore = false)
                if (artMode) applyState(stateBeforeArt, animate = false)
                val port = s.label.substringAfterLast(" · ")
                if (!hdmiNotice.isOpen) prevFocus = currentFocus
                blockContent(true, dimBar = false) // aviso liviano: el home y la barra siguen visibles detrás
                hdmiNotice.onSwitch = {
                    closeHdmiNotice(restore = false)
                    releaseVideoForPlayback()
                    if (!TvInputs.launch(this, s)) Toast.makeText(this, t("No se pudo abrir $port", "Could not open $port"), Toast.LENGTH_SHORT).show()
                }
                hdmiNotice.onDismiss = { closeHdmiNotice() }
                hdmiNotice.show(s.device ?: t("Dispositivo conectado", "Device connected"),
                    if (s.device != null) t("Conectado en $port", "Connected on $port") else t("Se conectó un equipo en $port", "A device connected on $port"), port)
            }
        }
    }

    private fun closeHdmiNotice(restore: Boolean = true) {
        if (!hdmiNotice.isOpen) return
        hdmiNotice.hide()
        blockContent(false)
        if (restore) restoreFocus()
    }

    /** Vigía de HDMI del home cuando el servicio de botones no está activo (con servicio, él avisa en cualquier app). */
    private var homeHdmiWatcher: cl.deco.shelf.service.HdmiWatcher? = null

    /** Botón Ajustes del control: elegir entre Ajustes de Lienzo, ajustes rápidos del televisor o ajustes del sistema. */
    private fun openSettingsChooser() {
        prevFocus = currentFocus
        blockContent(true)
        val opts = ArrayList<MenuPanel.Opt>()
        opts += MenuPanel.Opt(t("Ajustes de Lienzo", "Lienzo settings")) { closeMenu(restore = false); openSettings(keepPrev = true) }
        QuickAccess.tvQuick(this)?.let { quick ->
            opts += MenuPanel.Opt(t("Ajustes rápidos", "Quick settings"), t("Brillo, imagen, sonido", "Brightness, picture, sound")) {
                closeMenu()
                try { startActivity(quick.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
        opts += MenuPanel.Opt(t("Ajustes del televisor", "TV settings"), t("Sistema", "System")) { closeMenu(); QuickAccess.launch(this, QuickAccess.items.last()) }
        menu.showList(t("Ajustes", "Settings"), opts)
    }

    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            // Al despertar de STR (Suspend-to-RAM en TCL RTD2851M), el firmware puede soltar KeyService sin reiniciar
            if (started) ioData.execute { cl.deco.shelf.service.ServiceRepair.repairIfNeeded(applicationContext, ignoreCooldown = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        // Botón de Modo arte con Lienzo al frente: KeyService entrega la acción directo (startActivity pausaría y sacaría del modo arte)
        cl.deco.shelf.service.KeyMap.foreground = { handleRemoteAction(it, 0L) }
        updatePanel.onHostResume()
        ioData.execute { cl.deco.shelf.service.ServiceRepair.repairIfNeeded(applicationContext) }
        batsOncePerDay()
    }

    /** Halloween: murciélagos sobre el home la primera vez que se abre en el día. */
    private fun batsOncePerDay() {
        if (Theme.season == null) return
        val today = LocalDate.now().toString()
        if (prefs.sp.getString("season_bats_day", null) == today) return
        prefs.sp.edit().putString("season_bats_day", today).apply()
        val v = BatFlightView(this)
        rootView.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.play(14, 3200L) { rootView.post { rootView.removeView(v) } }
    }

    override fun onPause() {
        cl.deco.shelf.service.KeyMap.foreground = null
        if (ready) releaseVideoForPlayback()
        super.onPause()
    }

    /** Prioridad a la reproducción: suelta el decodificador del modo arte de inmediato, sin animación. */
    private fun releaseVideoForPlayback() = artController.releaseVideoForPlayback()

    override fun onStop() {
        if (!ready) { super.onStop(); return }
        started = false
        stopAt = System.currentTimeMillis()
        main.removeCallbacksAndMessages(null)
        artPolling = false
        netWatcher?.stop()
        runCatching { unregisterReceiver(pkgReceiver) }
        runCatching { unregisterReceiver(screenWakeReceiver) }
        runCatching { tvInputs?.unregisterCallback(inputsCallback) }
        homeHdmiWatcher?.stop(); homeHdmiWatcher = null
        if (today.visibility == View.VISIBLE) closeToday()
        closeInputs(restore = false)
        closeHdmiNotice(restore = false)
        dropLanTransfer()
        dropImport()
        if (settings.isOpen) closeSettings()
        if (health.isOpen) closeHealth()
        if (updatePanel.isOpen && !updatePanel.keepAcrossStop) closeUpdate()
        if (artMode) applyState(stateBeforeArt, animate = false)
        artController.onStop()
        // Se conserva la mitad más reciente de la caché (tiles del dock, tarjetas del Top Shelf): al volver no se redibujan
        loader.trimTo(0.5f)
        super.onStop()
    }

    private var startedAt = 0L
    private var lastHomeAt = 0L

    /**
     * Home dos veces rápido con Lienzo ya en pantalla: obra siguiente. Funciona con cualquier control (no usa el servicio
     * de botones). Al volver desde otra app Android entrega el Home con la Activity detenida, así que no cuenta como primera.
     */
    private fun doubleHome(intent: Intent?, inFront: Boolean): Boolean {
        if (intent?.action != Intent.ACTION_MAIN || intent.categories?.contains(Intent.CATEGORY_HOME) != true) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (!inFront || now - startedAt < 800) { lastHomeAt = 0; return false }
        if (now - lastHomeAt <= DOUBLE_HOME_MS) { lastHomeAt = 0; return true }
        lastHomeAt = now
        return false
    }

    override fun onNewIntent(intent: Intent?) {
        val inFront = started
        super.onNewIntent(intent)
        if (!ready) return
        if (CrashLog.safeMode(this)) { SafeActivity.launch(this); return }
        if (AlarmService.ringing) AlarmService.stop(this) // HOME también detiene alarma o temporizador
        // HOME con el launcher ya visible: cerrar overlays y volver al dock
        if (menu.visibility == View.VISIBLE) closeMenu(restore = false)
        closeInputs(restore = false)
        closeHdmiNotice(restore = false)
        if (today.visibility == View.VISIBLE) closeToday()
        dropLanTransfer()
        dropImport()
        if (settings.isOpen) closeSettings()
        if (health.isOpen) closeHealth()
        if (updatePanel.isOpen) closeUpdate()
        endMove()
        // Botones Entradas y Ajustes del control (KeyService): el home abre directo el desplegable o los ajustes
        if (handleRemoteAction(intent, 150L)) return
        if (state != HomeState.DOCK) applyState(HomeState.DOCK)
        focusFirst()
        scheduleArtMode()
        if (doubleHome(intent, inFront)) nextArt()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!ready) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Shelf ya no está visible: soltar el blur; la caché de íconos se recorta y solo se vacía con presión de memoria real
            artController.onTrimMemoryUiHidden()
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) loader.trim() else loader.trimTo(0.5f)
        }
    }

    override fun onDestroy() {
        if (!ready) { super.onDestroy(); return } // salió a modo seguro (o falló) antes de armar la UI
        Theme.listeners.remove(themeListener)
        ioData.shutdownNow()
        ioShelf.shutdownNow()
        ioArt.shutdownNow()
        loader.shutdown()
        lanPanel.close()
        updatePanel.close()
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
            topBar.setGreeting(t.hour, if (Theme.season != null) Seasonal.greeting(d, t.hour) else null)
            topBar.setWifiSsid(getWifiSsid())
            if (d != lastDate) {
                lastDate = d
                applySeason(d)
                topBar.setDate(Es.fecha(d))
                updateHoliday(d)
            }
            updateClockStatus()
            pollArtProgress()
            artController.onMinuteTick(now)
            if (prefs.weatherOn && now - lastWeather >= WEATHER_MS) { lastWeather = now; refreshWeather() }
            main.postDelayed(this, 60_000L - now % 60_000L + 50L)
        }
    }

    /** Cambio de red mientras el home es visible: actualiza el aviso del clima y, al volver la red, reintenta clima y descarga. */
    private fun onNetChanged(on: Boolean) {
        if (!ready || !started) return
        topBar.setOnline(on)
        topBar.setWifiSsid(getWifiSsid())
        if (on) {
            if (prefs.weatherOn) { lastWeather = System.currentTimeMillis(); refreshWeather() }
            ioArt.execute { try { graph.art.prefetch(1) } catch (_: Throwable) { } ; main.post { pollArtProgress() } }
        }
    }

    /**
     * Chip "Obras 12/60": lee el avance en el hilo de arte y, mientras la descarga esté activa, repite cada 5 s. Sin descarga
     * en curso no queda ningún Handler pendiente (el tick por minuto solo revisa si arrancó una).
     */
    private fun pollArtProgress() {
        if (!started || artPolling) return
        if (BuildConfig.FORCE_OFFLINE) { topBar.setArtProgress(ArtProgress.chipText(12, 60, true, Es.english)); return } // solo build de debug: muestra el chip
        artPolling = true
        ioArt.execute {
            val (d, t) = try { graph.art.downloadProgress() } catch (_: Throwable) { 0 to 0 }
            val dl = try { graph.art.downloading() } catch (_: Throwable) { false }
            main.post {
                artPolling = false
                if (!started) return@post
                topBar.setArtProgress(ArtProgress.chipText(d, t, dl, Es.english))
                if (dl) { artPolling = true; main.postDelayed(artProgressTick, 5000L) }
            }
        }
    }

    private fun refreshWeather() {
        ioData.execute {
            val w = if (BuildConfig.FORCE_OFFLINE) null else try { graph.weather.get(false) } catch (_: Throwable) { null }
            main.post {
                if (started && w != null) { weather = w; topBar.setWeather(w); today.setWeather(w); artController.scheduleSun() }
                // Sin datos (red lenta o caída): reintenta en ~1 min en vez de esperar el ciclo completo
                else if (w == null) lastWeather = System.currentTimeMillis() - WEATHER_MS + 60_000L
            }
        }
    }

    private fun updateHoliday(d: LocalDate) {
        val list = holidayCache[d.year]
        if (list == null) { loadHolidays(d.year); return }
        holidayName = list.firstOrNull { it.date == d }?.name
        topBar.setHoliday(holidayName ?: if (Theme.season != null) Seasonal.label(d) else null)
    }

    /** Entra o sale de la skin de temporada al cambiar el día o el ajuste. */
    private fun applySeason(d: LocalDate) {
        val s = Seasonal.paletteFor(d, prefs.seasonal)
        if (s === Theme.season) return
        Theme.season = s
        topBar.setSeason(s != null)
        artController.applyPalette(animate = true)
        if (ready) artController.nextArtNow() // cambia a (o sale de) las obras de temporada
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

    private fun applyTheme() {
        dockBg.setColor(Theme.dockTint())
        // Scrim: negro uniforme con 10% de mezcla del color oscuro de la paleta (neutro, no café)
        val d = Theme.dark
        val tint = android.graphics.Color.rgb((android.graphics.Color.red(d) * 0.10f).toInt(), (android.graphics.Color.green(d) * 0.10f).toInt(), (android.graphics.Color.blue(d) * 0.10f).toInt())
        scrimBg.colors = intArrayOf(Theme.withAlpha(tint, 0), Theme.withAlpha(tint, 0x66), Theme.withAlpha(tint, 0x66))
        topBar.setAccent(Theme.accent, Theme.light)
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
        if (appsReloading) {
            appsReloadPending = true
            return
        }
        appsReloading = true
        appsReloadPending = false
        ioData.execute {
            try {
                val apps = repo.load()
                // Las apps se muestran primero; el TvProvider (lento en frío) no retrasa el dock
                main.post { if (started) { allApps = apps; rebuild() } }
                // Watch Next: una sola pasada al iniciar y al cambiar paquetes; nada si el usuario ocultó Top Shelf y Continuar viendo
                if (!started || (!prefs.topShelf && !prefs.continueRow)) return@execute
                val t0 = android.os.SystemClock.elapsedRealtime()
                try { graph.shelf.preload(apps.map { it.component.packageName }.distinct()) } catch (e: Throwable) { warnOnce("preload", e) }
                Log.i("Shelf", "preload TvProvider: ${android.os.SystemClock.elapsedRealtime() - t0} ms (${apps.size} apps)")
                if (BuildConfig.TV_DUMP) cl.deco.shelf.shelf.TvDump.run(applicationContext)
                // Con la caché recién llena, el Top Shelf del tile enfocado se pinta sin esperar otro cambio de foco
                main.post { if (started && pendingPkg != null && state == HomeState.DOCK) { main.removeCallbacks(shelfRun); main.post(shelfRun) } }
            } finally {
                appsReloading = false
                if (appsReloadPending) {
                    appsReloadPending = false
                    main.post { if (started) reloadApps() }
                }
            }
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
        // Apps nuevas: instaladas tras el primer arranque con esta función; etiqueta "Nuevo" hasta abrirlas
        val pkgs = visible.map { it.component.packageName }.toSet()
        val known = store.known()
        var newSet = store.newApps()
        if (known == null) { if (pkgs.isNotEmpty()) store.setKnown(pkgs) }
        else {
            val fresh = visible.filter { it.component.packageName !in known }
            if (fresh.isNotEmpty()) { newSet = newSet + fresh.map { it.key }; store.setNewApps(newSet); store.setKnown(known + pkgs) }
        }
        newSet = newSet.filter { k -> visible.any { it.key == k } }.toSet()
        adapter.newKeys = newSet
        val idx = HashMap<String, Int>()
        store.order().forEachIndexed { i, k -> idx[k] = i }
        val byKey = visible.associateBy { it.key }
        // Máximo de favoritas del ajuste: las que sobran se muestran en la grilla
        val favs = favKeys.orEmpty().mapNotNull { byKey[it] }.distinctBy { it.key }.take(prefs.dockMax)
        val favSet = favs.map { it.key }.toSet()
        val grid = visible.filter { it.key !in favSet }.sortedWith(compareBy({ it.key in newSet }, { idx[it.key] ?: Int.MAX_VALUE })) +
            shelfSettingsEntry.copy(label = t("Ajustes de Lienzo", "Lienzo settings"))
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

    /** Columnas = min(7, apps). Grilla, encabezados y "Continuar viendo" parten del mismo margen seguro izquierdo (96 px). */
    private fun layoutGrid(count: Int) {
        val cols = count.coerceIn(1, GRID_COLS)
        (gridRv.layoutManager as GridLayoutManager).spanCount = cols
        val lp = gridRv.layoutParams as FrameLayout.LayoutParams
        val w = dp(cols * cellDp + 16)
        // El borde del primer tile (padding de la grilla 8 dp + padX 6 dp) cae exactamente en el margen seguro
        val gl = SAFE_LEFT - dp(8 + 6)
        if (lp.width != w || lp.leftMargin != gl) { lp.width = w; lp.leftMargin = gl; gridRv.layoutParams = lp }
        (gridHeader.layoutParams as FrameLayout.LayoutParams).leftMargin = SAFE_LEFT
        gridHeader.requestLayout()
        (contHeader.layoutParams as FrameLayout.LayoutParams).leftMargin = SAFE_LEFT
        contHeader.requestLayout()
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
            artController.onLeaveArtMode(animate)
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
        if (!(art && artController.hasVideoPlayer)) set(artView, 1f)
        // La obra se mantiene nítida en GRID; con desenfoque elegido (y bitmap generado) se funde a la versión blur.
        // Capas a pantalla completa con alpha 0 pasan a INVISIBLE: la GPU ni las considera
        val blurOn = grid && artController.ensureBlurForGrid()
        set(blurView, if (blurOn) 1f else 0f, 0f, if (blurOn) View.VISIBLE else View.INVISIBLE)
        // Brillo del fondo: filtro de color sobre la propia obra (sin capa a pantalla completa extra para la GPU)
        artController.applyDim(animate = false)
        // Scrim en degradado solo detrás de la grilla: 0% en y=220 a 45% en y=520
        set(gridScrim, if (grid) 1f else 0f, 0f, if (grid) View.VISIBLE else View.INVISIBLE)
        set(gridFade, if (grid) 1f else 0f, 0f, if (grid) View.VISIBLE else View.INVISIBLE)
        if (!grid) gridName.visibility = View.INVISIBLE
        set(gridHeader, if (grid) 0.85f else 0f, 0f, if (grid) View.VISIBLE else View.INVISIBLE)
        val showCont = grid && contHas
        set(contHeader, if (showCont) 0.85f else 0f, 0f, if (showCont) View.VISIBLE else View.INVISIBLE)
        set(contRv, if (showCont) 1f else 0f, 0f, if (showCont) View.VISIBLE else View.INVISIBLE)
        if (grid) set(gridRv, 1f, 0f) else set(gridRv, 1f, screenH.toFloat(), View.INVISIBLE)
        // Dock atenuado al 30% con la grilla abierta (sin foco, para no mezclarlo con la grilla)
        dockBox.descendantFocusability = if (grid) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        when {
            grid -> set(dockBox, 0f, dpf(40f), View.INVISIBLE) // oculto por completo con la grilla
            art -> set(dockBox, 0f)
            else -> set(dockBox, 1f)
        }
        val chrome = if (art) 0f else 1f
        set(topBar, chrome)
        set(shelfView, if (new == HomeState.DOCK) 1f else 0f)
        if (art) artController.scheduleArtVideo()
        if (grid) {
            main.removeCallbacks(shelfRun)
            shelfView.clear()
            shelfPkg = null
        }
    }

    private fun openGrid(focusSlot: Int = AppAdapter.GRID) {
        if (gridOpen) return
        // Continuar viendo: lee la lista ya cargada por preload (sin queries nuevas); sin items la fila no aparece
        val list = if (!prefs.continueRow) emptyList() else try { graph.shelf.continueWatching(12) } catch (_: Throwable) { emptyList() }
        contHas = list.isNotEmpty()
        contRv.setItems(list)
        val shift = if (contHas) 144 else 0
        // Solo se re-mide la grilla si cambió el desplazamiento (con o sin "Continuar viendo")
        if (gridRv.paddingTop != dp(160 + shift)) {
            gridRv.setPadding(dp(8), dp(160 + shift), dp(8), dp(24))
            (gridHeader.layoutParams as FrameLayout.LayoutParams).topMargin = dp(138 + shift)
            gridHeader.requestLayout()
        }
        applyState(HomeState.GRID)
        focusAt(focusSlot)
    }

    /** Vuelve al último tile del dock (acotado a las favoritas actuales); sin favoritas, a la grilla. */
    private fun focusDock() {
        if (adapter.favCount > 0) focusAt(lastDockSlot.coerceIn(0, adapter.favCount - 1)) else focusFirst()
    }

    /** Restaura el foco guardado al cerrar un panel; si esa vista ya no está (dock más chico, app oculta), el primer tile. */
    private fun restoreFocus() {
        prevFocus?.requestFocus()
        val f = currentFocus
        if (f == null || !f.isShown) focusFirst()
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
        if (e.key in adapter.newKeys) {
            // primera apertura: desaparece la etiqueta "Nuevo"
            val rest = adapter.newKeys - e.key
            store.setNewApps(store.newApps() - e.key)
            adapter.newKeys = rest
            adapter.refreshSlot(adapter.slotOf(e.key, false))
        }
        releaseVideoForPlayback()
        try { startActivity(repo.launchIntent(e)) } catch (_: Exception) {
            Toast.makeText(this, t("No se pudo abrir ${e.label}", "Could not open ${e.label}"), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLongClick(e: AppEntry, slot: Int) {
        if (moving) return
        if (e.component.className == IconOverrides.SHELF_SETTINGS_CLASS) { openSettings(); return }
        openMenu(e, slot)
    }

    override fun onFocus(e: AppEntry, slot: Int) {
        artController.glowFor(e) // fondo "Color dinámico": el resplandor sigue a la app enfocada (dock y grilla)
        if (!adapter.isFav(slot)) {
            gridName.text = e.label
            main.post {
                val cell = currentFocus as? FocusCell ?: return@post
                // Coordenadas por layout (no por pantalla): no dependen de la animación de subida de la grilla
                val w = gridName.paint.measureText(e.label)
                val cx = gridRv.left + cell.left + cell.content.left + cell.content.width / 2f
                gridName.translationX = (cx - w / 2f).coerceIn(dp(24).toFloat(), resources.displayMetrics.widthPixels - w - dp(24))
                gridName.translationY = (gridRv.top + cell.top + cell.content.top + cell.content.height + dp(10)).toFloat()
                gridName.visibility = View.VISIBLE
            }
            return
        }
        lastDockSlot = slot
        val pkg = e.component.packageName
        // Con datos en caché el contenido anterior se reemplaza directo a los 120 ms (sin vaciar y volver a aparecer);
        // solo se vacía si la app nueva aún no tiene datos y habrá que consultar el provider
        if (pkg != shelfPkg && !(prefs.topShelf && graph.shelf.peek(pkg) != null)) { shelfView.clear(); shelfPkg = null }
        pendingPkg = pkg
        shelfWanted = pkg
        pendingEntry = e
        main.removeCallbacks(shelfRun)
        // Con caché el Top Shelf aparece casi al instante; sin ella se espera un poco más para no consultar el provider por
        // cada tile que se cruza al navegar rápido
        val cached = prefs.topShelf && graph.shelf.peek(pkg) != null
        main.postDelayed(shelfRun, if (cached) SHELF_CACHED_MS else SHELF_QUERY_MS)
    }

    /**
     * Top Shelf de la app enfocada: pinta lo que haya en caché y relee el provider en segundo plano solo si no hay datos o
     * vencieron (stale-while-revalidate). Las lecturas de un tile que ya perdió el foco se descartan antes de consultar.
     */
    private val shelfRun = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        if (!prefs.topShelf) { shelfPkg = pkg; showAppName(); return@Runnable }
        val hit = graph.shelf.peek(pkg)
        if (hit != null) showShelf(pkg, hit.first)
        if (hit != null && hit.second) return@Runnable
        ioShelf.execute {
            if (shelfWanted != pkg) return@execute
            val items = try { graph.shelf.refresh(pkg) } catch (e: Throwable) { warnOnce("itemsFor", e); emptyList() }
            main.post { if (hit == null || items != hit.first) showShelf(pkg, items) }
        }
    }

    /** Hilo de Top Shelf: paquete aún enfocado (copia de pendingPkg visible desde el executor). */
    @Volatile private var shelfWanted: String? = null

    private fun showShelf(pkg: String, items: List<TopShelfItem>) {
        if (!started || pendingPkg != pkg || state != HomeState.DOCK) return
        // Con el foco dentro del carrusel no se reemplazan las tarjetas (perdería el foco)
        if (currentFocus?.isIn(shelfView) == true) return
        shelfPkg = pkg
        if (items.isNotEmpty()) shelfView.show(items) else showAppName()
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
        releaseVideoForPlayback()
        try {
            startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, t("No se pudo abrir el contenido", "Could not open content"), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Modo arte (sin teclas por 45 s) ----------

    private val artModeRun = Runnable { enterArtMode() }

    private fun scheduleArtMode() {
        main.removeCallbacks(artModeRun)
        if (prefs.artEnabled) main.postDelayed(artModeRun, prefs.artWaitSec * 1000L)
    }

    private fun enterArtMode() {
        if (!started || !prefs.artEnabled || moving || menu.visibility == View.VISIBLE || inputsPopup.isOpen || hdmiNotice.isOpen || today.visibility == View.VISIBLE || settings.isOpen || health.isOpen || updatePanel.isOpen || importPanel.isOpen || lanPanel.isOpen) return
        if (state != HomeState.DOCK && state != HomeState.TOPBAR) return
        stateBeforeArt = state
        applyState(HomeState.ART_MODE)
    }

    private fun exitArtMode() {
        if (state == HomeState.ART_MODE) applyState(stateBeforeArt)
    }

    // ---------- Overlays ----------

    // ---------- Ajustes de Shelf ----------

    /** [keepPrev]: abierto desde el menú contextual, que ya guardó el foco de la app (el foco actual es una opción del menú). */
    private fun openSettings(keepPrev: Boolean = false) {
        if (!keepPrev) prevFocus = currentFocus
        blockContent(true)
        settings.open(settingsPages.root())
    }

    private fun getWifiSsid(): String? {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val actNet = cm?.activeNetwork
            val caps = if (actNet != null) cm.getNetworkCapabilities(actNet) else null
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "Ethernet"
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            var ssid = info?.ssid
            if (ssid != null) {
                ssid = ssid.replace("\"", "").trim()
                if (ssid.isNotEmpty() && !ssid.contains("unknown", ignoreCase = true) && ssid != "0x") {
                    return ssid
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Entradas (píldora o botón Input del control): desplegable bajo la píldora con HDMI, AV y TV en vivo. Sin entradas en
     * TvInputManager se abre el panel del fabricante si existe.
     */
    private var inputsLoading = false

    /**
     * Cambios de entradas mientras el home es visible: un equipo HDMI-CEC (consola, deco) que se enciende o se retira
     * aparece o desaparece como entrada hija. Con el desplegable abierto se actualiza en el lugar; si no, solo la píldora.
     */
    private val inputsCallback = object : android.media.tv.TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = refreshInputs()
        override fun onInputRemoved(inputId: String) = refreshInputs()
        override fun onInputUpdated(inputId: String) = refreshInputs()
        override fun onInputStateChanged(inputId: String, state: Int) = refreshInputs() // "Conectado" al día con el desplegable abierto
    }

    private fun refreshInputs() {
        if (!started) return
        ioShelf.execute {
            val sources = try { TvInputs.sources(this) } catch (_: Throwable) { return@execute }
            val has = sources.isNotEmpty() || TvInputs.systemPanel(this) != null
            main.post {
                if (!started) return@post
                topBar.setInputsAvailable(has)
                if (inputsPopup.isOpen && sources.isNotEmpty()) inputsPopup.update(sources)
            }
        }
    }

    private val tvInputs by lazy { getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager }

    /** La lista se arma en ioData (binder de TvInputManager y PackageManager) y el desplegable se abre al volver al main. */
    private fun openInputs() {
        if (inputsPopup.isOpen || inputsLoading) return
        inputsLoading = true
        ioShelf.execute { // ioShelf: no espera detrás de una consulta lenta de clima o feriados (ioData)
            val sources = try { TvInputs.sources(this) } catch (_: Throwable) { emptyList() }
            main.post { inputsLoading = false; if (started) showInputs(sources) }
        }
    }

    private fun showInputs(sources: List<TvInputs.Source>) {
        if (inputsPopup.isOpen) return
        if (sources.isEmpty()) {
            val panel = TvInputs.systemPanel(this)
            if (panel != null) try { startActivity(panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
            Toast.makeText(this, t("Este equipo no tiene entradas de video", "No video inputs on this device"), Toast.LENGTH_SHORT).show()
            return
        }
        if (artMode) applyState(stateBeforeArt, animate = false)
        if (state == HomeState.GRID) closeGrid(refocus = false)
        val anchor = topBar.inputsAnchor() ?: return
        prevFocus = anchor
        applyState(HomeState.TOPBAR, animate = false)
        blockContent(true, dimBar = false) // el desplegable cuelga de la píldora: la barra queda visible
        inputsPopup.open(anchor, sources)
    }

    private fun closeInputs(restore: Boolean = true) {
        if (!inputsPopup.isOpen) return
        inputsPopup.close()
        blockContent(false)
        if (restore) prevFocus?.requestFocus()
    }

    private fun openWizard() {
        prevFocus = currentFocus
        blockContent(true)
        settings.open(settingsPages.wizard())
    }

    override fun finishSettings() { closeSettings() }

    override fun requestTvPermission() { requestPermissions(arrayOf(PERM_TV), 1) }

    // ---------- Mis fondos: importar desde USB ----------

    /** Permiso estándar de medios (READ_EXTERNAL_STORAGE en Android 12) y luego la grilla. */
    override fun importFromUsb() {
        if (!MediaImporter.hasPermission(this)) {
            pendingImport = true
            requestPermissions(MediaImporter.permissions(), REQ_MEDIA)
            return
        }
        openImport()
    }

    private fun openImport() {
        if (!settings.isOpen) { prevFocus = currentFocus; blockContent(true) }
        settings.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importPanel.open()
    }

    private fun closeImport() {
        importPanel.close()
        settings.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        if (settings.isOpen) settings.rebuild() else { blockContent(false); prevFocus?.requestFocus() }
    }

    /** Cierra la grilla sin tocar el foco (onStop / HOME: después se cierran los ajustes). */
    private fun dropImport() {
        if (importPanel.isOpen) importPanel.close()
        settings.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    override fun openLanTransfer() {
        if (lanPanel.isOpen) return
        if (!settings.isOpen) { prevFocus = currentFocus; blockContent(true) }
        settings.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        lanPanel.open()
    }

    private fun closeLanTransfer() {
        if (lanPanel.isOpen) lanPanel.close()
        settings.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        if (settings.isOpen) settings.rebuild() else { blockContent(false); prevFocus?.requestFocus() }
    }

    private fun dropLanTransfer() {
        if (lanPanel.isOpen) lanPanel.close()
        settings.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    /** Se importó o borró algo: si el fondo usa Mis fondos se vuelve a elegir obra. */
    override fun fondosChanged() {
        if (prefs.bgSource != 0) artController.nextArtNow()
    }

    private fun closeSettings() {
        cl.deco.shelf.service.KeyMap.stopLearning()
        settings.close()
        blockContent(false)
        restoreFocus()
    }

    /** Renombrar una app: página de ajustes con EditText (teclado del sistema); el nombre persiste por paquete. */
    private fun openRename(e: AppEntry) {
        blockContent(true)
        val pkg = e.component.packageName
        val orig = allApps.firstOrNull { it.component.packageName == pkg }?.label ?: e.label
        fun done(name: String?) { store.setName(pkg, name); closeSettings(); rebuild() }
        settings.open(Page(t("Renombrar ${orig}", "Rename ${orig}"), listOf(
            Row.Input(t("Nuevo nombre", "New name"), e.label, false) { done(it.ifBlank { null }) },
            Row.Act(t("Restaurar nombre original", "Restore original name"), { orig }) { done(null) },
        )))
    }

    override fun changed(key: String) {
        when (key) {
            ShelfPrefs.BG_ONLINE, ShelfPrefs.BG_SOURCE, ShelfPrefs.COLLECTION -> artController.nextArtNow()
            ShelfPrefs.BG_BRIGHTNESS -> artController.applyDim(animate = true)
            ShelfPrefs.NIGHT_DIM -> { artController.scheduleSun(); artController.applyDim(animate = true) }
            ShelfPrefs.BLUR_LEVEL -> {
                if (prefs.blurLevel == 0) artController.clearBlur() else artController.rebuildBlur()
                applyState(state, animate = false)
            }
            ShelfPrefs.DYNAMIC_COLORS -> { Theme.dynamic = prefs.dynamicColors; artController.applyPalette(animate = true) }
            ShelfPrefs.SEASONAL -> { val d = LocalDate.now(); applySeason(d); updateHoliday(d) }
            ShelfPrefs.ART_ON, ShelfPrefs.ART_WAIT -> scheduleArtMode()
            ShelfPrefs.TOP_SHELF, ShelfPrefs.CONTINUE_ROW -> {
                shelfView.clear(); shelfPkg = null
                if (!prefs.continueRow) contRv.setItems(emptyList()) // suelta las imágenes de las tarjetas
                if (prefs.topShelf || prefs.continueRow) reloadApps() else main.post(shelfRun) // al reactivar se vuelve a leer el TvProvider
            }
            ShelfPrefs.CLOCK24 -> { main.removeCallbacks(tick); main.post(tick) }
            ShelfPrefs.WEATHER_ON -> {
                topBar.setWeatherEnabled(prefs.weatherOn)
                if (prefs.weatherOn) { lastWeather = System.currentTimeMillis(); refreshWeather() }
            }
            ShelfPrefs.NIGHT_ON, ShelfPrefs.NIGHT_FROM, ShelfPrefs.NIGHT_TO, ShelfPrefs.NIGHT_IDLE -> {
                NightMode.refresh(this); updateClockStatus()
            }
            ShelfPrefs.DOCK_MAX -> rebuild() // 4 a 7 favoritas: el tile no cambia de tamaño, el dock se centra con las que haya
            ShelfPrefs.ICON_ORIGINAL -> regenerateIcons()
            ShelfPrefs.UPDATE_AUTO -> if (!prefs.updateAuto) topBar.setUpdateHint(null) {} // el chip no espera al próximo regreso al home
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
                if (key == ShelfPrefs.ENGLISH) {
                    topBar.refreshLocale()
                    contHeader.text = t("Continuar viendo", "Continue watching")
                    gridHeader.text = t("Todas las apps", "All apps")
                    hint.text = t("Mueve con las flechas. OK para confirmar", "Use arrows to move. OK to confirm")
                    rebuild()
                    if (settings.isOpen) settings.replacePages(settingsPages.localeStack())
                }
            }
        }
    }

    /**
     * Indicadores junto al reloj. Se refresca por minuto (tick) y en cada cambio; en el último minuto de un temporizador
     * pasa a cada segundo, solo con Shelf visible. Sin temporizadores activos no queda ningún Handler periódico.
     */
    private fun updateClockStatus() {
        main.removeCallbacks(secTick)
        val now = System.currentTimeMillis()
        val store = ClockStore(this)
        val soonest = store.timers().filter { it.running && it.endAt > now }.minOfOrNull { it.endAt - now }
        val timerTxt = soonest?.let { if (it > 60_000) "${(it + 59_999) / 60_000} min" else "0:%02d".format((it + 999) / 1000) }
        if (soonest != null && soonest <= 60_000 && started) main.postDelayed(secTick, 1000)
        val nextAlarm = store.alarms().filter { it.enabled }.mapNotNull { ClockScheduler.nextTime(it, now) }.minOrNull()
            ?.takeIf { it - now < 12 * 3600_000L }
        val alarmTxt = nextAlarm?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it)) }
        val sleepTxt = cl.deco.shelf.clock.SleepTimer.formatChip(prefs.sleepUntilMs, now, Es.english)
        topBar.setStatus(timerTxt, alarmTxt, NightMode.active(prefs), sleepTxt) // lee night_active en cada actualización: la luna sigue al evento nocturno aunque no haya onStart
    }

    private val openOnStartRun = Runnable { runOpenOnStart() }

    private fun scheduleOpenOnStart() {
        main.removeCallbacks(openOnStartRun)
        val mode = prefs.openOnStart
        if (mode == "home") return
        val bootMs = prefs.sp.getLong("boot_pending_ms", 0L)
        if (bootMs <= 0L) return
        val age = android.os.SystemClock.elapsedRealtime() - bootMs
        prefs.sp.edit().putLong("boot_pending_ms", 0L).apply()
        if (age in 0..90_000L) main.postDelayed(openOnStartRun, 4000L)
    }

    private fun runOpenOnStart() {
        if (!started || settings.isOpen || menu.visibility == View.VISIBLE || inputsPopup.isOpen || today.visibility == View.VISIBLE || lanPanel.isOpen) return
        when (prefs.openOnStart) {
            "livetv" -> cl.deco.shelf.service.KeyMap.liveTvApp(this)?.let { pkg ->
                releaseVideoForPlayback()
                cl.deco.shelf.service.KeyMap.launch(this, pkg)
            }
            "last_input" -> {
                val lastId = prefs.sp.getString("last_input_id", null) ?: return
                ioShelf.execute {
                    val src = try { TvInputs.sources(this).firstOrNull { it.id == lastId } } catch (_: Throwable) { null }
                    if (src != null) main.post {
                        if (started) { releaseVideoForPlayback(); TvInputs.launch(this, src) }
                    }
                }
            }
        }
    }

    /** Lectura de salud; en builds con -PlienzoForceHealth (solo pruebas) simula CMA baja (60 MB) y usa tiempos de 1/360. */
    private fun healthSnapshot(): cl.deco.shelf.util.DeviceHealth.Snapshot {
        val h = cl.deco.shelf.util.DeviceHealth.read()
        return if (BuildConfig.FORCE_HEALTH) h.copy(cmaFreeKb = 60L * 1024, cmaTotalKb = 544L * 1024) else h
    }

    private val healthTiming get() = if (BuildConfig.FORCE_HEALTH) HealthNotice.Timing.FAST else HealthNotice.Timing.NORMAL
    private val healthStore by lazy { HealthNoticeStore(prefs.sp) }

    private val checkHealth = Runnable { evaluateHealth(confirming = false) }

    /**
     * Aviso "Reiniciar recomendado" con reglas antifatiga (ver HealthNotice): se evalúa al volver al home, como máximo cada 6 h,
     * nunca con reproducción ni en modo arte; una CMA baja se confirma 20 s después y exige 2 evaluaciones bajas separadas.
     */
    private fun evaluateHealth(confirming: Boolean) {
        if (!started) return
        val t = healthTiming
        val now = System.currentTimeMillis()
        val up = android.os.SystemClock.elapsedRealtime()
        var st = healthStore.load()
        if (HealthNotice.rebooted(st, up)) st = HealthNotice.afterReboot(st, up)
        val blocked = artController.hasVideoPlayer || artMode || MemoryCleaner.playbackActive(this)
        if (confirming && !blocked) {
            val h2 = healthSnapshot()
            st = HealthNotice.onEvaluation(st, now, h2.cmaFreeKb, h2.cmaTotalKb, h2.uptimeMs, t)
        } else if (!confirming && HealthNotice.canEvaluate(st, now, t, blocked)) {
            val h = healthSnapshot()
            if (h.needsConfirm) {
                // Primera lectura baja: se sella la evaluación (tope de 6 h) y se repite a los 20 s antes de contarla
                st = st.copy(lastEvalMs = now, lastUptimeMs = up)
                healthStore.save(st)
                topBar.setRestartHint(HealthNotice.visible(st, now, t)) { openHealth() } // mientras se confirma, el chip refleja el estado vigente
                Log.i("Shelf", "salud: lectura baja, se confirma en ${cl.deco.shelf.util.DeviceHealth.CONFIRM_MS / 1000} s")
                main.postDelayed({ evaluateHealth(confirming = true) }, cl.deco.shelf.util.DeviceHealth.CONFIRM_MS)
                return
            }
            st = HealthNotice.onEvaluation(st, now, h.cmaFreeKb, h.cmaTotalKb, h.uptimeMs, t)
        } else st = st.copy(lastUptimeMs = up)
        healthStore.save(st)
        val show = !blocked && HealthNotice.visible(st, now, t)
        Log.i("Shelf", "salud: confirmando=$confirming bloqueado=$blocked bajas=${st.lowCount} chip=$show ultimoAviso=${st.lastNoticeMs}")
        topBar.setRestartHint(show) { openHealth() }
    }

    /** "Recordarme más tarde": oculta el aviso y no vuelve antes de 24 h. */
    private fun dismissHealthNotice() {
        val t = healthTiming
        healthStore.save(HealthNotice.onDismissed(healthStore.load(), System.currentTimeMillis(), t.cooldownMs))
        topBar.setRestartHint(false) {}
    }

    override fun openHealth() {
        healthStore.save(HealthNotice.onOpened(healthStore.load()))
        topBar.setRestartHint(false) {}
        // Desde Ajustes, prevFocus ya guarda el foco del home; desde el aviso se guarda el foco actual
        if (settings.isOpen) closeSettings() else prevFocus = currentFocus
        blockContent(true)
        health.open()
    }

    private fun closeHealth() {
        health.close()
        blockContent(false)
        prevFocus?.requestFocus()
        if (currentFocus == null || !currentFocus!!.isShown) focusFirst() // p. ej. el aviso se ocultó con "Recordarme más tarde"
    }

    // ---------- Actualizaciones (GitHub) ----------

    private val updateStore by lazy { UpdateStore(this) }
    private val updateChecker by lazy { UpdateChecker(this, updateStore) }
    private var updateChecking = false
    private val updateTiming = UpdateRules.Timing.NORMAL
    private val checkUpdate = Runnable { evaluateUpdate() }

    /**
     * Aviso "Actualización vX disponible", mismas reglas antifatiga que Salud del deco (ver UpdateRules): la búsqueda solo ocurre al
     * volver al home, como máximo cada 24 h, con la opción activada, con red y sin reproducción ni modo arte; el chip se oculta solo
     * a las 12 h y "Más tarde" lo silencia 24 h. Sin servicios ni alarmas.
     */
    private fun evaluateUpdate() {
        if (!started) return
        val now = System.currentTimeMillis()
        val blocked = artController.hasVideoPlayer || artMode || MemoryCleaner.playbackActive(this)
        var st = UpdateRules.afterInstalled(updateStore.load(), updateChecker.installedCode())
        if (!prefs.updateAuto) { topBar.setUpdateHint(null) {}; updateStore.save(st); return }
        val online = netWatcher?.online ?: true
        if (online && UpdateRules.canCheck(st, now, updateTiming, blocked, auto = true) && !updateChecking) {
            updateStore.save(st)
            runUpdateCheck(manual = false)
            return
        }
        updateStore.save(st)
        showUpdateChip(st, now, blocked)
    }

    private fun showUpdateChip(st: UpdateRules.State, now: Long, blocked: Boolean) {
        val info = updateStore.info
        val show = !blocked && prefs.updateAuto && info != null && UpdateRules.visible(st, now, updateTiming)
        if (show && info != null) topBar.setUpdateHint(info.chipText()) { openUpdate(info) } else topBar.setUpdateHint(null) {}
    }

    /** Búsqueda en el hilo de datos; [manual] omite los límites de frecuencia y avisa el resultado. */
    private fun runUpdateCheck(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        ioData.execute {
            val out = updateChecker.check()
            main.post {
                updateChecking = false
                if (!ready) return@post
                val now = System.currentTimeMillis()
                var st = updateStore.load()
                val failed = out is UpdateChecker.Outcome.Failed
                if (!failed) {
                    val code = (out as? UpdateChecker.Outcome.Available)?.info?.versionCode ?: 0L
                    st = UpdateRules.onCheck(st, now, updateTiming, code)
                } else st = st.copy(lastCheckMs = now) // un fallo también cuenta: no se reintenta antes de 24 h (la búsqueda manual siempre está)
                updateStore.save(st)
                updateStore.status = when (out) {
                    is UpdateChecker.Outcome.Available -> t("${UpdateRules.displayVersion(out.info.versionName)} disponible: OK para instalar", "${UpdateRules.displayVersion(out.info.versionName)} available: press OK to install")
                    is UpdateChecker.Outcome.UpToDate -> out.text
                    is UpdateChecker.Outcome.Failed -> out.text
                }
                if (manual) {
                    if (settings.isOpen) settings.rebuild()
                    if (out is UpdateChecker.Outcome.Available) { if (started) openUpdate(out.info) }
                    else Toast.makeText(this, updateStore.status, Toast.LENGTH_SHORT).show()
                } else if (started) {
                    showUpdateChip(st, now, artController.hasVideoPlayer || artMode || MemoryCleaner.playbackActive(this))
                }
            }
        }
    }

    override fun checkUpdates() {
        val known = updateStore.info
        if (known != null && UpdateRules.isApplicable(known, updateChecker.installedCode(), android.os.Build.VERSION.SDK_INT) && updateStore.load().availableCode > 0) {
            openUpdate(known); return
        }
        Toast.makeText(this, t("Buscando actualizaciones...", "Checking for updates..."), Toast.LENGTH_SHORT).show()
        runUpdateCheck(manual = true)
    }

    override fun updateStatus(): String = if (updateChecking) t("Buscando...", "Checking...") else updateStore.status.ifEmpty { t("Nunca", "Never") }

    private fun dismissUpdateNotice() {
        updateStore.save(UpdateRules.onDismissed(updateStore.load(), System.currentTimeMillis(), updateTiming.cooldownMs))
        topBar.setUpdateHint(null) {}
    }

    private fun openUpdate(info: UpdateInfo) {
        updateStore.save(UpdateRules.onOpened(updateStore.load()))
        topBar.setUpdateHint(null) {}
        if (settings.isOpen) closeSettings() else prevFocus = currentFocus
        blockContent(true)
        updatePanel.open(info)
    }

    private fun closeUpdate() {
        updatePanel.close()
        blockContent(false)
        prevFocus?.requestFocus()
        if (currentFocus == null || !currentFocus!!.isShown) focusFirst()
    }

    override fun freeMemory() {
        trimCaches()
        Toast.makeText(this, t("Memoria liberada: ${pss()}", "Memory freed: ${pss()}"), Toast.LENGTH_SHORT).show()
    }

    /** Suelta las cachés propias: LRU de tiles y, fuera de la grilla, el bitmap blur (se regenera al abrir la grilla). */
    private fun trimCaches() {
        loader.trim()
        if (state != HomeState.GRID) artController.onTrimMemoryUiHidden()
    }

    /** Espacio libre en /data: se revisa como máximo una vez al día (al volver al home), sin polling. */
    private fun checkLowSpace() {
        val day = System.currentTimeMillis() / 86_400_000L
        if (prefs.sp.getLong("space_day", -1L) != day) {
            val free = try { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
            prefs.sp.edit().putLong("space_day", day).putBoolean("space_low", free < 500L * 1024 * 1024).apply()
        }
        topBar.setLowSpace(prefs.sp.getBoolean("space_low", false)) { QuickAccess.launch(this, QuickAccess.storage) }
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

    override fun nextArt() = artController.nextArtNow()

    override fun pss(): String = "%.1f MB".format(Debug.getPss() / 1024.0)

    override fun exportBackup(): String = BackupManager.exportToFile(this)

    override fun backups(): List<File> = BackupManager.listBackups(this)

    override fun restoreBackup(f: File) {
        try {
            BackupManager.restoreFromFile(this, f)
            recreate() // relee todo desde las prefs restauradas
        } catch (e: Exception) {
            Toast.makeText(this, t("Respaldo inválido: ${e.message}", "Invalid backup: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    /** Bloquea el foco del home bajo un panel; [dimBar] atenúa la barra superior para que no compita con el panel (Entradas no). */
    private fun blockContent(b: Boolean, dimBar: Boolean = true) {
        content.descendantFocusability = if (b) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (!artMode) topBar.animate().alpha(if (b && dimBar) 0f else 1f).setDuration(150).start()
    }

    /** OK sobre el reloj: panel Reloj (Calendario, Temporizador, Alarma). */
    private fun openClock() {
        prevFocus = currentFocus
        blockContent(true)
        today.openClock(weather, LocalDate.now())
    }

    /** OK sobre el clima: panel Clima, solo pronóstico de 7 días. */
    private fun openWeatherPanel() {
        prevFocus = currentFocus
        blockContent(true)
        today.openWeather(weather, LocalDate.now())
    }

    private fun closeToday() {
        today.close()
        blockContent(false)
        restoreFocus()
    }

    private fun openMenu(e: AppEntry, slot: Int) {
        prevFocus = currentFocus
        blockContent(true)
        val fav = adapter.isFav(slot)
        val isSystem = try {
            val ai = packageManager.getApplicationInfo(e.component.packageName, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) { false }
        val uninstallAction: (() -> Unit)? = if (!isSystem && e.component.packageName != packageName) {
            {
                closeMenu()
                try {
                    val uri = Uri.fromParts("package", e.component.packageName, null)
                    startActivity(Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    Toast.makeText(this, t("No se puede desinstalar", "Cannot uninstall"), Toast.LENGTH_SHORT).show()
                }
            }
        } else null

        menu.show(
            e.label, fav,
            onFav = { closeMenu(restore = false); toggleFav(e, slot, fav) },
            onMove = { closeMenu(); startMove(slot) },
            onInfo = {
                closeMenu()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", e.component.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            },
            onHide = { closeMenu(restore = false); hideApp(e, slot) },
            onHidden = { closeMenu(restore = false); openHiddenList() },
            onRename = { closeMenu(restore = false); openRename(e) },
            onUninstall = uninstallAction,
            onSettings = { closeMenu(restore = false); openSettings(keepPrev = true) },
            onQuick = { q -> closeMenu(); QuickAccess.launch(this, q) },
        )
    }

    private fun closeMenu(restore: Boolean = true) {
        menu.hide()
        blockContent(false)
        if (restore) restoreFocus()
    }

    private fun toggleFav(e: AppEntry, slot: Int, isFav: Boolean) {
        if (!isFav && adapter.favCount >= prefs.dockMax) {
            Toast.makeText(this, t("Dock lleno (máximo ${prefs.dockMax})", "Dock full (max ${prefs.dockMax})"), Toast.LENGTH_SHORT).show()
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
        blockContent(true)
        val hid = allApps.filter { it.key in store.hidden() }
        val opts = if (hid.isEmpty()) {
            listOf(MenuPanel.Opt(t("No hay apps ocultas", "No hidden apps")) { closeMenu() })
        } else {
            hid.map { a -> MenuPanel.Opt(t("Mostrar ${a.label}", "Show ${a.label}")) { closeMenu(restore = false); store.setHidden(store.hidden() - a.key); rebuildThen { if (adapter.favCount > 0) 0 else AppAdapter.GRID } } }
        }
        menu.showList(t("Apps ocultas", "Hidden apps"), opts)
    }

    private fun rebuildThen(pos: () -> Int) {
        rebuild(focusSlot = -1)
        main.post { focusAt(pos().coerceAtLeast(0)) }
        // Si el slot calculado ya no existe (p. ej. se ocultó la última favorita), el foco no queda en el aire
        main.postDelayed({ val f = currentFocus; if (started && (f == null || !f.isShown)) focusFirst() }, 400)
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
        if (!ready) return super.dispatchKeyEvent(e)
        main.removeCallbacks(openOnStartRun)
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
        if (lanPanel.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeLanTransfer(); return true }
            if (k == KeyEvent.KEYCODE_MENU) return true
            return super.dispatchKeyEvent(e)
        }
        if (importPanel.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down && !importPanel.back()) closeImport(); return true }
            if (k == KeyEvent.KEYCODE_MENU) return true
            return super.dispatchKeyEvent(e)
        }
        if (settings.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down && !settings.back()) closeSettings(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (health.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down && !health.back()) closeHealth(); return true }
            if (k == KeyEvent.KEYCODE_MENU) return true
            return super.dispatchKeyEvent(e)
        }
        if (updatePanel.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down && !updatePanel.back()) closeUpdate(); return true }
            if (k == KeyEvent.KEYCODE_MENU) return true
            return super.dispatchKeyEvent(e)
        }
        if (hdmiNotice.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeHdmiNotice(); return true }
            if (k == KeyEvent.KEYCODE_MENU) return true
            if (hdmiNotice.handleKey(e)) return true
            return super.dispatchKeyEvent(e)
        }
        if (inputsPopup.isOpen) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeInputs(); return true }
            if (k == KeyEvent.KEYCODE_MENU) return true
            if (inputsPopup.handleKey(e)) return true
            return super.dispatchKeyEvent(e)
        }
        if (menu.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeMenu(); return true }
            return super.dispatchKeyEvent(e)
        }
        if (today.visibility == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeToday(); return true }
            if (k == KeyEvent.KEYCODE_HOME || k == KeyEvent.KEYCODE_POWER) return super.dispatchKeyEvent(e)
            // Panel Reloj: navegación normal por foco; panel Clima: solo lectura
            return if (today.clockMode) super.dispatchKeyEvent(e) else true
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
                KeyEvent.KEYCODE_MENU -> return true // el menú contextual sobre el modo mover perdía el reordenamiento en curso
            }
        }
        if (k == KeyEvent.KEYCODE_MENU && down && topBar.healthFocused()) { openHealth(); return true }
        if (k == KeyEvent.KEYCODE_MENU && down) {
            val slot = focusedSlot()
            val en = if (slot != null) adapter.entryAt(slot) else null
            if (en != null && en.component.className != IconOverrides.SHELF_SETTINGS_CLASS) openMenu(en, slot!!) else openSettings()
            return true
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (down) when (state) {
                HomeState.GRID -> if (adapter.favCount > 0) closeGrid() // sin dock la grilla es el home: cerrarla dejaba el foco atrapado
                HomeState.TOPBAR -> focusDock()
                else -> if (currentFocus?.isIn(shelfView) == true) focusDock() else focusFirst()
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
            // Sin favoritas en el dock, DOWN desde la barra superior baja a la grilla
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && state == HomeState.TOPBAR && adapter.favCount == 0 && adapter.gridCount > 0) {
                openGrid()
                return true
            }
            val inShelf = currentFocus?.isIn(shelfView) == true
            // Carrusel del Top Shelf: UP sube a la barra superior, DOWN vuelve al tile del dock que lo abrió
            if (inShelf && k == KeyEvent.KEYCODE_DPAD_DOWN && adapter.favCount > 0) {
                focusAt(lastDockSlot.coerceIn(0, adapter.favCount - 1)); return true
            }
            if (inShelf && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // En los extremos del carrusel el foco no salta a otra zona de la pantalla
                val dir = if (k == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                val next = currentFocus?.focusSearch(dir) // pasa por RecyclerView: trae tarjetas fuera de pantalla
                if (next == null || !next.isIn(shelfView)) return true
            }
            if (inShelf && k == KeyEvent.KEYCODE_DPAD_UP) {
                if (topBar.focusInputs()) applyState(HomeState.TOPBAR)
                return true
            }
            // UP desde el dock: primero las tarjetas del Top Shelf si están visibles; si no, la barra superior
            if (k == KeyEvent.KEYCODE_DPAD_UP && state == HomeState.DOCK && !gridOpen && slot != null && adapter.isFav(slot)) {
                main.removeCallbacks(shelfRun)
                if (shelfView.focusCards()) return true
                if (topBar.focusInputs()) {
                    applyState(HomeState.TOPBAR)
                    return true
                }
            }
            // DOWN desde el dock sube la grilla; UP desde la primera fila la baja
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && !gridOpen && slot != null && adapter.isFav(slot) && adapter.gridCount > 0) {
                openGrid()
                return true
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && gridOpen && currentFocus?.parent === contRv) {
                closeGrid()
                return true
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP && gridOpen && slot != null && slot - AppAdapter.GRID < (gridRv.layoutManager as GridLayoutManager).spanCount) {
                // primera fila: sube a "Continuar viendo" si existe; si no, cierra la grilla
                if (contHas && contRv.childCount > 0) { contRv.firstVisible()?.requestFocus(); return true }
                if (adapter.favCount > 0) { closeGrid(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Soporte básico de rueda/scroll de air-mouse sobre el home (overlays delegan al scroll estándar). */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!ready) return super.onGenericMotionEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(vScroll) >= 0.05f) {
                main.removeCallbacks(openOnStartRun)
                scheduleArtMode()
                if (state == HomeState.ART_MODE) {
                    exitArtMode()
                    return true
                }
                val overlayOpen = lanPanel.isOpen || importPanel.isOpen || settings.isOpen || health.isOpen ||
                    updatePanel.isOpen || hdmiNotice.isOpen || inputsPopup.isOpen ||
                    menu.visibility == View.VISIBLE || today.visibility == View.VISIBLE
                if (overlayOpen) return super.onGenericMotionEvent(event)
                if (moving) return true
                if (vScroll < 0f) {
                    when (state) {
                        HomeState.TOPBAR -> if (adapter.favCount > 0) focusAt(lastDockSlot.coerceIn(0, adapter.favCount - 1)) else if (adapter.gridCount > 0) openGrid()
                        HomeState.DOCK -> if (adapter.gridCount > 0) openGrid()
                        HomeState.GRID -> gridRv.smoothScrollBy(0, dp(220))
                        else -> {}
                    }
                } else {
                    when (state) {
                        HomeState.GRID -> {
                            if (gridRv.computeVerticalScrollOffset() > 0) gridRv.smoothScrollBy(0, -dp(220))
                            else if (adapter.favCount > 0) closeGrid()
                        }
                        HomeState.DOCK -> if (topBar.focusInputs()) applyState(HomeState.TOPBAR)
                        else -> {}
                    }
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    @Deprecated("Home ya es la raíz")
    override fun onBackPressed() { /* launcher: ignorar back */ }

    companion object {
        const val TILE_W = 224
        const val TILE_H = 126
        const val GRID_COLS = 7
        /** Margen seguro izquierdo: 96 px a 1080p. */
        val SAFE_LEFT get() = 96
        /** Ventana para el doble Home (obra siguiente). */
        const val DOUBLE_HOME_MS = 600L
        const val WEATHER_MS = 20L * 60_000L
        const val SHELF_CACHED_MS = 120L
        const val SHELF_QUERY_MS = 300L
        const val PERM_TV = "android.permission.READ_TV_LISTINGS"
        const val REQ_MEDIA = 2
        /** Intents de KeyService para los botones Entradas y Ajustes del control. */
        const val ACTION_OPEN_INPUTS = cl.deco.shelf.service.KeyMap.ACTION_OPEN_INPUTS
        const val ACTION_OPEN_SETTINGS = cl.deco.shelf.service.KeyMap.ACTION_OPEN_SETTINGS
    }
}
