package cl.deco.shelf.ui

import android.os.Handler
import android.os.Looper
import cl.deco.shelf.data.weather.Geocoding
import android.content.Intent
import android.provider.Settings
import cl.deco.shelf.Graph
import cl.deco.shelf.R
import cl.deco.shelf.clock.NightMode
import cl.deco.shelf.data.art.SpaceRules
import cl.deco.shelf.data.art.VideoLibrary
import cl.deco.shelf.data.art.VideoPolicy
import cl.deco.shelf.service.KeyMap
import cl.deco.shelf.settings.ShelfPrefs
import java.io.File
import java.util.concurrent.Executors

/** Acciones que el panel de ajustes delega en la pantalla principal. */
interface SettingsHost {
    /** Un ajuste cambió: aplicarlo en vivo. [key] es la clave de ShelfPrefs. */
    fun changed(key: String)
    fun hiddenCount(): Int
    fun restoreHidden()
    fun resetOrder()
    fun regenerateIcons()
    fun nextArt()
    fun pss(): String
    fun exportBackup(): String
    fun backups(): List<File>
    fun restoreBackup(f: File)
    fun requestTvPermission()
    fun finishSettings()
    /** Abre el panel Salud del deco (cierra Ajustes). */
    fun openHealth()
    /** "Importar desde USB": pide el permiso de medios si falta y abre la grilla de importación. */
    fun importFromUsb()
    /** Abre el servidor efímero LAN + QR para subir fondos o respaldos desde el celular. */
    fun openLanTransfer()
    /** Cambió el contenido de Mis fondos (borrado) o la fuente: recargar el fondo si corresponde. */
    fun fondosChanged()
    /** "Buscar actualizaciones": consulta GitHub ahora y, si hay versión nueva, abre el panel de actualización. */
    fun checkUpdates()
    /** Texto de estado de la última búsqueda (para Ajustes > Info). */
    fun updateStatus(): String
    /** "Liberar memoria de Lienzo": suelta las cachés propias (tiles, imágenes de tarjetas, blur fuera de la grilla). */
    fun freeMemory()
}

/** Construye las páginas del panel de ajustes a partir de ShelfPrefs. */
class SettingsPages(private val ctx: android.content.Context, private val panel: SettingsPanel, private val p: ShelfPrefs, private val host: SettingsHost, private val version: String) {

    private val halfHours = List(48) { "%02d:%02d".format(it / 2, it % 2 * 30) }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var confirmReset = false
    private var lastBackup = ""

    private fun <T> choice(label: String, key: String, names: List<String>, values: List<T>, get: () -> T, set: (T) -> Unit) =
        Row.Choice(label, names, { values.indexOf(get()).coerceAtLeast(0) }, { set(values[it]); host.changed(key) })

    private fun toggle(label: String, key: String, on: String, off: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Row.Choice(label, listOf(on, off), { if (get()) 0 else 1 }, { set(it == 0); host.changed(key) }, isToggle = true)

    // Sin emojis: la fuente de color se cargaría solo para esto (varios MB de PSS) y no combina con los íconos del sistema
    // Lienzo primero (es lo que se abre desde "Ajustes de Lienzo") y una sola sección Televisor sin repetidos: antes Imagen,
    // Sonido y Ajustes de Android aparecían dos veces (Televisor y Accesos rápidos)
    fun root(): Page = Page(t("Ajustes", "Settings"), listOf(
        Row.Section("Lienzo"),
        Row.Act(t("Pantalla y fondos", "Display & backgrounds"), { sourceName() }, icon = R.drawable.ic_q_display, chevron = true) { panel.push(screenAndBackgroundPage()) },
        Row.Act(t("Modo arte y protector", "Art mode & screensaver"), { if (p.artEnabled) t("Activado", "On") else t("Desactivado", "Off") }, icon = R.drawable.ic_s_art, chevron = true) { panel.push(artAndScreensaverPage()) },
        Row.Act(t("Barra superior e inicio", "Top bar & home"), { "${if (p.clock24) "24 h" else "12 h"} · ${p.dockMax} ${t("en el dock", "in dock")}" }, icon = R.drawable.ic_s_home, chevron = true) { panel.push(interfacePage()) },
        Row.Act(t("Apps e íconos", "Apps & icons"), { val n = host.hiddenCount(); if (n == 0) t("Ninguna oculta", "None hidden") else "$n ${if (n == 1) t("oculta", "hidden") else t("ocultas", "hidden")}" }, icon = R.drawable.ic_s_apps, chevron = true) { confirmReset = false; panel.push(appsAndIconsPage()) },
        Row.Act(t("Control remoto", "Remote control"), { if (KeyMap.serviceRunning(ctx)) t("Botones activos", "Buttons active") else t("Requiere activar", "Setup needed") }, icon = R.drawable.ic_s_remote, chevron = true) { panel.push(remotePage()) },
        Row.Act(t("Sistema y energía", "System & power"), { if (p.nightOn) t("Apagado nocturno activo", "Night auto-off on") else t("Apagado nocturno inactivo", "Night auto-off off") }, icon = R.drawable.ic_sleep, chevron = true) { panel.push(powerAndSystemPage()) },
        Row.Act(t("Mantenimiento e información", "Maintenance & about"), { version }, icon = R.drawable.ic_s_info, chevron = true) { panel.push(aboutAndMaintenancePage()) },
    ) + tvRows())

    /** Pila de páginas para reconstruir en caliente al cambiar idioma desde Ubicación y región. */
    fun localeStack(): List<Page> = listOf(root(), interfacePage(), locationPage())

    /** Sección "Televisor": ajustes rápidos del fabricante (si existen) y accesos del sistema, cada uno una sola vez. */
    private fun tvRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row.Section(t("Televisor", "TV"))
        QuickAccess.tvQuick(ctx)?.let { quick ->
            rows += Row.Act(t("Ajustes rápidos", "Quick settings"), { t("Brillo, imagen, sonido, temporizador", "Brightness, picture, sound, timer") }, icon = R.drawable.ic_q_settings, chevron = true) { host.finishSettings(); openActivity(quick) }
        }
        // Wi-Fi, Bluetooth, Imagen, Sonido, Almacenamiento y, al final, todos los ajustes del sistema
        for (q in QuickAccess.items) {
            val label = if (q === QuickAccess.items.last()) t("Todos los ajustes del sistema", "All system settings") else q.label
            rows += Row.Act(label, icon = q.icon, chevron = true) { QuickAccess.launch(ctx, q) }
        }
        return rows
    }

    /** Botones de apps del control (Netflix, Prime Video, YouTube y los de TCL reasignados) vía el servicio de accesibilidad. */
    private var confirmKeyReset = false

    /** Asignación dinámica de botones (cualquier control): aprender, cambiar acción, app de TV en vivo y restablecer. */
    fun remotePage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Section(t("Estado", "Status"))
        rows += Row.Info(t("Servicio de botones", "Button service")) {
            when {
                KeyMap.serviceRunning(ctx) -> t("Activo", "Active")
                KeyMap.serviceEnabled(ctx) -> t("Habilitado pero detenido por el sistema (en TCL falta el permiso de arranque)", "Enabled but stopped by system (TCL needs autostart permission)")
                else -> t("Inactivo", "Inactive")
            }
        }
        if (!KeyMap.serviceRunning(ctx)) {
            rows += Row.Act(t("Activar sin PC", "Activate without PC"), { t("Con la depuración por red del equipo", "Via device network debugging") }, chevron = true) { panel.push(setupPage()) }
            rows += Row.Act(t("Abrir ajustes de accesibilidad", "Open accessibility settings"), chevron = true) { openActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        rows += Row.Section(t("Asignar", "Assign"))
        rows += Row.Act(t("Asignar un botón", "Assign a button"), { t("Presiona el botón y elige qué hace", "Press a button and pick an action") }, chevron = true) { panel.push(learnPage()) }
        rows += Row.Act(t("App de TV en vivo", "Live TV app"), { KeyMap.liveTvApp(ctx)?.let { KeyMap.appLabel(ctx, it) } ?: t("Sin app (función original)", "No app (system default)") }, chevron = true) { panel.push(liveTvPage()) }
        val map = KeyMap.all(ctx).toList().sortedBy { KeyMap.name(it.first) }
        if (map.isNotEmpty()) {
            rows += Row.Section(t("Botones asignados", "Assigned buttons"))
            for ((code, action) in map) rows += Row.Act(KeyMap.name(code), { KeyMap.actionLabel(ctx, action) }, chevron = true) { panel.push(actionPage(code)) }
        }
        rows += Row.Act(t("Restablecer valores iniciales", "Reset to defaults"), { if (confirmKeyReset) t("OK de nuevo para confirmar", "Press OK again to confirm") else "" }) {
            if (confirmKeyReset) { confirmKeyReset = false; KeyMap.resetDefaults(ctx); panel.replace(remotePage()) } else confirmKeyReset = true
        }
        rows += Row.Section(t("Compatibilidad", "Compatibility"))
        rows += toggle(t("Passthrough de gamepad", "Gamepad passthrough"), ShelfPrefs.GAMEPAD_PASSTHROUGH, t("Activado", "On"), t("Desactivado", "Off"), { p.gamepadPassthrough }, { p.gamepadPassthrough = it })
        rows += toggle(t("Pausar filtro al subir/bajar volumen", "Pause filter on volume keys"), ShelfPrefs.VOLUME_PAUSES_FILTER, t("Activado (Android 14)", "On (Android 14)"), t("Desactivado", "Off"), { p.volumePausesFilter }, { p.volumePausesFilter = it })
        rows += Row.Info("") { t("Flechas, OK, Atrás, Inicio, volumen y encendido no se reasignan. Los botones sin asignar mantienen su función original.", "Arrows, OK, Back, Home, volume and power are never remapped. Unassigned buttons keep their default behavior.") }
        return Page(t("Control remoto", "Remote control"), rows, onShow = { KeyMap.stopLearning(); confirmKeyReset = false })
    }

    private fun learnPage(): Page {
        val title = t("Asignar un botón", "Assign a button")
        return Page(title, listOf(
            Row.Info("") { t("Presiona ahora en el control el botón que quieres asignar.", "Press the remote button you want to assign now.") },
            Row.Info("") { t("Funciona con cualquier control: botones de apps, colores, Guía, Asistente o propios del fabricante.", "Works with any remote: app shortcuts, color keys, Guide, Assistant, or OEM buttons.") },
        ), onShow = {
            KeyMap.startLearning { code ->
                if (panel.isOpen && panel.currentTitle() == title) { panel.back(); panel.push(actionPage(code)) }
            }
        })
    }

    /** Qué hace un botón: acciones de Lienzo o abrir cualquier app instalada. */
    private fun actionPage(code: Int): Page {
        // Vuelve a Control remoto con la lista recalculada (un botón recién aprendido aparece en "Botones asignados")
        fun pick(action: String?) { KeyMap.set(ctx, code, action); panel.back(); panel.replace(remotePage()) }
        val rows = ArrayList<Row>()
        rows += Row.Info(t("Ahora", "Current")) { KeyMap.actionLabel(ctx, KeyMap.all(ctx)[code]) }
        rows += Row.Section(t("Acciones", "Actions"))
        rows += Row.Act(t("Entradas de Lienzo", "Lienzo Inputs")) { pick(KeyMap.INPUTS) }
        rows += Row.Act(t("Menú de ajustes", "Settings menu"), { t("Lienzo, rápidos o del televisor", "Lienzo, quick settings, or TV settings") }) { pick(KeyMap.SETTINGS) }
        rows += Row.Act(t("TV en vivo", "Live TV"), { KeyMap.liveTvApp(ctx)?.let { KeyMap.appLabel(ctx, it) } ?: t("Sin app (función original)", "No app (system default)") }) { pick(KeyMap.LIVETV) }
        rows += Row.Act(t("Todas las apps", "All apps"), { t("Grilla de Lienzo", "Lienzo app grid") }) { pick(KeyMap.APPS) }
        rows += Row.Act(t("Modo arte", "Art mode"), { t("Obra a pantalla completa", "Fullscreen artwork") }) { pick(KeyMap.ART) }
        rows += Row.Act(t("Función original del sistema", "Original system action")) { pick(null) }
        rows += Row.Section(t("Abrir una app", "Launch an app"))
        for ((label, pkg) in launchableApps()) rows += Row.Act(label) { pick(KeyMap.app(pkg)) }
        return Page(t("Botón: ${KeyMap.name(code)}", "Button: ${KeyMap.name(code)}"), rows)
    }

    private fun liveTvPage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Act(t("Predeterminado", "Default"), { if (KeyMap.installed(ctx, KeyMap.DEFAULT_LIVETV)) "Movistar TV" else t("Sin app (función original)", "No app (system default)") }) {
            KeyMap.setLiveTvApp(ctx, null); panel.back(); panel.replace(remotePage())
        }
        rows += Row.Section(t("Apps", "Apps"))
        for ((label, pkg) in launchableApps()) rows += Row.Act(label) { KeyMap.setLiveTvApp(ctx, pkg); panel.back(); panel.replace(remotePage()) }
        rows += Row.Info("") { t("Se usa en el botón TV del control y en la opción TV en vivo de Entradas.", "Used by the remote's TV button and Live TV option in Inputs.") }
        return Page(t("App de TV en vivo", "Live TV app"), rows)
    }

    /** Apps con ícono en el launcher (TV o móvil), ordenadas por nombre, sin Lienzo. */
    private fun launchableApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val res = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return res.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }.distinctBy { it.first }
            .map { it.second to it.first }.sortedBy { it.first.lowercase() }
    }

    fun screenAndBackgroundPage(): Page = Page(t("Pantalla y fondos", "Display & backgrounds"), listOf(
        Row.Section(t("Fondo", "Background")),
        // Fuente: obras de arte, imágenes propias o "Color dinámico" (fondo oscuro con el color de la app enfocada)
        choice(t("Fuente del fondo", "Background source"), ShelfPrefs.BG_SOURCE, sourceNames, listOf(0, 1, 2, ShelfPrefs.BG_DYNAMIC), { p.bgSource }, { p.bgSource = it }),
    ) + collectionRows() + listOf(
        choice(t("Rotación de obra", "Artwork rotation"), ShelfPrefs.BG_INTERVAL, listOf("15 min", "30 min", "60 min", t("Diario", "Daily")), listOf(15, 30, 60, 1440), { p.bgIntervalMin }, { p.bgIntervalMin = it }),
        Row.Act(t("Cambiar obra ahora", "Next artwork now")) { host.nextArt() },
        Row.Act(t("Mis fondos", "My backgrounds"), { t("Importar y gestionar imágenes y videos propios", "Import and manage custom images & videos") }, chevron = true) { panel.push(myBackgroundsPage()) },
        choice(t("Brillo del fondo", "Background brightness"), ShelfPrefs.BG_BRIGHTNESS, listOf("60%", "70%", "80%", "90%", "100%"), listOf(60, 70, 80, 90, 100), { p.bgBrightness }, { p.bgBrightness = it }),
        toggle(t("Atenuar de noche", "Dim at night"), ShelfPrefs.NIGHT_DIM, t("Sí", "Yes"), t("No", "No"), { p.nightDim }, { p.nightDim = it }),
        choice(t("Desenfoque en grilla", "App grid blur"), ShelfPrefs.BLUR_LEVEL, listOf(t("Ninguno", "None"), t("Bajo", "Low"), t("Medio", "Medium"), t("Alto", "High")), listOf(0, 1, 2, 3), { p.blurLevel }, { p.blurLevel = it }),
        toggle(t("Colores", "Colors"), ShelfPrefs.DYNAMIC_COLORS, t("Dinámicos desde la obra", "Dynamic from artwork"), t("Neutros", "Neutral"), { p.dynamicColors }, { p.dynamicColors = it }),
        toggle(t("Temporada", "Seasonal"), ShelfPrefs.SEASONAL, t("Halloween (24 oct a 1 nov)", "Halloween (Oct 24 - Nov 1)"), t("Apagada", "Off"), { p.seasonal }, { p.seasonal = it }),
    ))

    /** Todas las colecciones (imágenes o videos), General primero. */
    private fun allCollections(): List<String> =
        cl.deco.shelf.data.art.Collections.sorted(Graph.from(ctx).userArt.collections() + VideoLibrary.collections(ctx))

    /**
     * "Colección": qué grupo de Mis fondos usan el fondo, el protector y el video. Aparece en cuanto hay algo importado;
     * si la activa ya no existe (se borró) vuelve a "Todas".
     */
    private fun collectionRows(): List<Row> {
        val cols = allCollections()
        if (p.collection.isNotEmpty() && p.collection !in cols) p.collection = ""
        if (cols.isEmpty()) return emptyList()
        val values = listOf("") + cols
        val names = listOf(t("Todas", "All")) + cols
        return listOf(choice(t("Colección de Mis fondos", "My backgrounds collection"), ShelfPrefs.COLLECTION, names, values, { p.collection }, { p.collection = it }))
    }

    fun artAndScreensaverPage(): Page = Page(t("Modo arte y protector", "Art mode & screensaver"), listOf(
        Row.Section(t("Modo arte", "Art mode")),
        toggle(t("Modo arte", "Art mode"), ShelfPrefs.ART_ON, t("Activado", "On"), t("Desactivado", "Off"), { p.artEnabled }, { p.artEnabled = it }),
        choice(t("Espera", "Idle delay"), ShelfPrefs.ART_WAIT, listOf("30 s", "45 s", "2 min", "5 min"), listOf(30, 45, 120, 300), { p.artWaitSec }, { p.artWaitSec = it }),
        toggle(t("Usar video", "Use video"), ShelfPrefs.ART_VIDEO, t("Sí", "Yes"), t("No", "No"), { p.artVideo }, { p.artVideo = it }),
        Row.Section(t("Protector de pantalla", "Screensaver")),
        toggle(t("Usar video", "Use video"), ShelfPrefs.DREAM_VIDEO, t("Sí", "Yes"), t("No", "No"), { p.dreamVideo }, { p.dreamVideo = it }),
        choice(t("Intervalo de obras", "Artwork interval"), ShelfPrefs.DREAM_INTERVAL, listOf("1 min", "3 min", "5 min"), listOf(1, 3, 5), { p.dreamIntervalMin }, { p.dreamIntervalMin = it }),
    ) + videoRows())

    /**
     * Elección del video (modo arte y protector) junto a "Usar video": antes estaba escondida en Mis fondos y, sin ningún
     * video en el equipo, "Usar video: Sí" no hacía nada sin decirlo.
     */
    private fun videoRows(): List<Row> {
        val (tokens, names) = videoOptions()
        val rows = ArrayList<Row>()
        rows += Row.Section("Video")
        if (tokens.isEmpty()) rows += Row.Info(t("Sin videos en el equipo", "No videos on device")) { t("Importa uno propio para que el modo arte y el protector muestren video.", "Import your own video so Art Mode and Screensaver can play it.") }
        else rows += Row.Choice("Video", names, { tokens.indexOf(p.videoChoice).coerceAtLeast(0) }, { p.videoChoice = tokens[it]; host.changed(ShelfPrefs.VIDEO_CHOICE) })
        rows += Row.Act(t("Agregar o quitar videos", "Add or remove videos"), { t("Mis fondos", "My backgrounds") }, chevron = true) { panel.push(myBackgroundsPage()) }
        rows += Row.Info("") { t("MP4, MOV, M4V o MKV (H.264 o H.265) hasta 1080p. Puedes bajarlo con Downloader al almacenamiento y luego importarlo.", "MP4, MOV, M4V or MKV (H.264 or H.265) up to 1080p. Download to storage or transfer via QR.") }
        return rows
    }

    /** Opciones reales de video: Monet solo si está en el equipo, cada video propio y "Alternar todos" con más de uno. */
    private fun videoOptions(): Pair<List<String>, List<String>> {
        val monet = VideoLibrary.monet(ctx) != null
        val videos = VideoLibrary.userVideos(ctx)
        val tokens = ArrayList<String>(); val names = ArrayList<String>()
        if (monet) { tokens += VideoPolicy.MONET; names += "Monet" }
        for (v in videos) { tokens += VideoPolicy.userChoice(v.name); names += t("Propio: ", "Custom: ") + short(v.file.nameWithoutExtension, 22) }
        if (tokens.size > 1) { tokens += VideoPolicy.ALL; names += t("Alternar todos", "Shuffle all") }
        return tokens to names
    }

    fun interfacePage(): Page = Page(t("Barra superior e inicio", "Top bar & home"), listOf(
        Row.Section(t("Reloj y barra superior", "Clock & top bar")),
        choice(t("Reloj", "Clock"), ShelfPrefs.CLOCK24, listOf("24 h", "12 h"), listOf(true, false), { p.clock24 }, { p.clock24 = it }),
        toggle(t("Clima", "Weather"), ShelfPrefs.WEATHER_ON, t("Activado", "On"), t("Desactivado", "Off"), { p.weatherOn }, { p.weatherOn = it }),
        Row.Act(t("Ubicación y región", "Location & region"), { p.cityName }, chevron = true) { panel.push(locationPage()) },
        Row.Section(t("Pantalla de inicio", "Home screen")),
        choice(t("Favoritas en el dock (máx.)", "Dock favorites (max)"), ShelfPrefs.DOCK_MAX, listOf("4", "5", "6", "7"), listOf(4, 5, 6, 7), { p.dockMax }, { p.dockMax = it }),
        Row.Section(t("Contenido de las apps", "App channels")),
        // Al cambiar se redibuja la página: el texto de abajo depende de ambos ajustes
        toggle(t("Recomendados al enfocar una app", "Top Shelf on app focus"), ShelfPrefs.TOP_SHELF, t("Mostrar", "Show"), t("Ocultar", "Hide"), { p.topShelf }, { p.topShelf = it; main.post { panel.rebuild() } }),
        toggle(t("Continuar viendo (Todas las apps)", "Continue Watching (All apps)"), ShelfPrefs.CONTINUE_ROW, t("Mostrar", "Show"), t("Ocultar", "Hide"), { p.continueRow }, { p.continueRow = it; main.post { panel.rebuild() } }),
        Row.Info("") { if (!p.topShelf && !p.continueRow) t("Con ambos ocultos Lienzo no lee el historial ni las recomendaciones de tus apps.", "When both are hidden, Lienzo does not read app playback history or recommendations.") else t("Lo que muestran viene de cada app (Netflix, YouTube, Disney+ y otras).", "Content comes from each installed app (Netflix, YouTube, Disney+, etc.).") },
    ))

    fun appsAndIconsPage(): Page = Page(t("Apps e íconos", "Apps & icons"), listOf(
        Row.Section(t("Íconos", "Icons")),
        choice(t("Estilo de íconos", "Icon style"), ShelfPrefs.ICON_ORIGINAL, listOf(t("Adaptado", "Adapted"), t("Original sin procesar", "Unprocessed original")), listOf(false, true), { p.iconOriginal }, { p.iconOriginal = it }),
        Row.Act(t("Regenerar íconos", "Regenerate icons")) { host.regenerateIcons() },
        Row.Section(t("Gestión de apps", "App management")),
        Row.Act(t("Restaurar apps ocultas", "Restore hidden apps"), { val n = host.hiddenCount(); if (n == 0) t("Ninguna oculta", "None hidden") else "$n ${if (n == 1) t("oculta", "hidden") else t("ocultas", "hidden")}" }) { host.restoreHidden() },
        Row.Act(t("Restablecer orden y favoritas", "Reset order & favorites"), { if (confirmReset) t("OK de nuevo para confirmar", "Press OK again to confirm") else "" }) {
            if (confirmReset) { confirmReset = false; host.resetOrder() } else confirmReset = true
        },
    ))

    fun powerAndSystemPage(): Page = Page(t("Sistema y energía", "System & power"), listOfNotNull(
        Row.Section(t("Inicio al encender", "Startup behavior")),
        choice(t("Al encender el equipo", "On power on"), ShelfPrefs.OPEN_ON_START, listOf(t("Inicio (Lienzo)", "Home (Lienzo)"), t("TV en vivo", "Live TV"), t("Última entrada", "Last input")), listOf("home", "livetv", "last_input"), { p.openOnStart }, { p.openOnStart = it }),
        Row.Section(t("Apagado nocturno", "Night auto-off")),
        toggle(t("Apagado por inactividad", "Idle sleep"), ShelfPrefs.NIGHT_ON, t("Activado", "On"), t("Desactivado", "Off"), { p.nightOn }, { p.nightOn = it }),
        Row.Choice(t("Desde", "From"), halfHours, { (p.nightFromMin / 30).coerceIn(0, 47) }, { p.nightFromMin = it * 30; host.changed(ShelfPrefs.NIGHT_FROM) }),
        Row.Choice(t("Hasta", "Until"), halfHours, { (p.nightToMin / 30).coerceIn(0, 47) }, { p.nightToMin = it * 30; host.changed(ShelfPrefs.NIGHT_TO) }),
        choice(t("Inactividad", "Idle timeout"), ShelfPrefs.NIGHT_IDLE, listOf("30 min", "60 min", "90 min", "120 min"), listOf(30, 60, 90, 120), { p.nightIdleMin }, { p.nightIdleMin = it }),
        Row.Info(t("Permiso", "Permission")) { if (NightMode.hasPermission(ctx)) t("Concedido", "Granted") else t("Falta el permiso de ajustes seguros", "Missing WRITE_SECURE_SETTINGS permission") },
        if (NightMode.hasPermission(ctx)) null else Row.Act(t("Conceder sin PC", "Grant without PC"), { t("Con la depuración por red del equipo", "Via device network debugging") }, chevron = true) { panel.push(setupPage()) },
    ))

    // ---- Configuración sin PC (ADB local) ----

    private var setupStatus = ""
    private var setupBusy = false
    private var confirmStock = false

    /** Lo que antes pedía adb desde un computador: permisos, servicio de botones, inicio/protector y volver al original. */
    fun setupPage(): Page = Page(t("Configuración sin PC", "PC-free setup"), listOf(
        Row.Section(t("Estado", "Status")),
        Row.Info(t("Permisos", "Permissions")) {
            val ok = listOf("android.permission.READ_TV_LISTINGS", "android.permission.WRITE_SECURE_SETTINGS")
                .count { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (ok == 2) t("Concedidos", "Granted") else t("Faltan ${2 - ok} de 2", "Missing ${2 - ok} of 2")
        },
        Row.Info(t("Servicio de botones", "Button service")) { if (KeyMap.serviceRunning(ctx)) t("Activo", "Active") else t("Inactivo", "Inactive") },
        Row.Info(t("Resultado", "Result")) { setupStatus.ifEmpty { t("Sin ejecutar", "Not run yet") } },
        Row.Section(t("Acciones", "Actions")),
        Row.Act(t("Conceder permisos y activar botones", "Grant permissions & enable buttons"), { t("Top Shelf, apagado nocturno, alarma y control", "Top Shelf, night sleep, alarm, and remote") }) {
            runSetup { cl.deco.shelf.service.SelfSetup.permissionsAndKeys(ctx, it) }
        },
        Row.Act(t("Usar Lienzo como inicio y protector", "Set Lienzo as Home & screensaver")) { runSetup { cl.deco.shelf.service.SelfSetup.makeDefault(ctx, it) } },
        Row.Act(t("Deshabilitar launcher original (ahorra RAM)", "Disable stock launcher (saves RAM)"), { t("Libera 150-250 MB en Google TV; reversible", "Frees 150-250 MB on Google TV; reversible") }) {
            runSetup { cl.deco.shelf.service.SelfSetup.disableStockLauncher(ctx, it) }
        },
        Row.Act(t("Limpiar bloatware (reversible)", "Debloat bloatware (reversible)"), { t("Deshabilita apps de relleno conocidas sin borrar datos", "Disables known bloatware without deleting data") }) {
            runSetup { cl.deco.shelf.service.SelfSetup.debloat(ctx, it) }
        },
        Row.Act(t("Restaurar apps deshabilitadas", "Restore disabled apps"), { val n = p.disabledByLienzo.size; if (n == 0) t("Ninguna registrada", "None recorded") else t("$n deshabilitadas por Lienzo", "$n disabled by Lienzo") }) {
            runSetup { cl.deco.shelf.service.SelfSetup.restoreDebloat(ctx, it) }
        },
        Row.Act(t("Volver al launcher original", "Restore stock launcher"), { if (confirmStock) t("OK de nuevo para confirmar", "Press OK again to confirm") else t("Lienzo queda instalado", "Lienzo remains installed") }) {
            if (!confirmStock) { confirmStock = true; panel.rebuild() }
            else { confirmStock = false; runSetup { cl.deco.shelf.service.SelfSetup.restoreStock(ctx, it) } }
        },
        Row.Info(t("Requisito", "Requirement")) { t("Depuración por red (o USB) activa en Opciones de desarrollador. La primera vez la TV pregunta \"¿Permitir depuración por USB?\": marca \"Permitir siempre\" y elige Permitir.", "Network (or USB) debugging enabled in Developer Options. On first run TV prompts \"Allow USB debugging?\": check \"Always allow\" and tap Allow.") },
        Row.Info(t("Seguridad", "Security")) { t("Lienzo solo ejecuta estos comandos fijos. Si no usas la depuración, desactívala al terminar: deja el equipo abierto a la red local.", "Lienzo runs only these fixed commands. Disable debugging afterward if you don't need it.") },
    ), onShow = { confirmStock = false })

    /** Ejecuta un paso de SelfSetup en segundo plano y muestra el avance en la fila Resultado. */
    private fun runSetup(task: (() -> Unit) -> String) {
        if (setupBusy) return
        setupBusy = true
        setupStatus = t("Conectando…", "Connecting…")
        panel.rebuild()
        io.execute {
            val r = task { main.post { setupStatus = t("En la TV marca \"Permitir siempre\" y elige Permitir (hasta 60 s)", "On TV check \"Always allow\" and tap Allow (up to 60 s)"); panel.rebuild() } }
            main.post { setupBusy = false; setupStatus = r; panel.rebuild() }
        }
    }

    fun aboutAndMaintenancePage(): Page = Page(t("Mantenimiento e información", "Maintenance & about"), listOf(
        // Memoria de video, RAM, tiempo encendido y "Liberar cachés" viven en el panel Salud (antes se repetían aquí)
        Row.Section(t("Diagnóstico y salud", "Diagnostics & health")),
        Row.Act(t("Salud del equipo", "Device health"), { if (cl.deco.shelf.util.DeviceHealth.read().restartRecommended) t("Reinicio recomendado", "Restart recommended") else t("Memoria, encendido y cachés", "Memory, uptime & caches") }, chevron = true) { host.openHealth() },
        Row.Bar(t("Almacenamiento (/data)", "Storage (/data)"), { storageText() }, { storageUsed() }),
        Row.Info(t("Memoria de Lienzo (PSS)", "Lienzo memory (PSS)")) { "${host.pss()} · ${t("objetivo en reposo < 40 MB", "idle target < 40 MB")}" },
        Row.Section(t("Actualizaciones", "Updates")),
        Row.Act(t("Buscar actualizaciones", "Check for updates"), { host.updateStatus() }) { host.checkUpdates() },
        choice(t("Actualizaciones automáticas", "Automatic updates"), ShelfPrefs.UPDATE_AUTO, listOf(t("Buscar y avisar", "Check & notify"), t("Desactivadas", "Off")), listOf(true, false), { p.updateAuto }, { p.updateAuto = it }),
        Row.Section(t("Copia de seguridad", "Backup")),
        Row.Act(t("Exportar configuración", "Export settings")) { lastBackup = host.exportBackup(); panel.rebuild() },
        Row.Info(t("Respaldo", "Backup path")) { lastBackup.ifEmpty { t("Carpeta: Android/data/cl.deco.shelf/files/backup (adb pull)", "Folder: Android/data/cl.deco.shelf/files/backup (adb pull)") } },
        Row.Act(t("Restaurar configuración", "Restore settings"), chevron = true) { panel.push(restorePage()) },
        Row.Act(t("Respaldo desde el celular (QR)", "Phone backup (QR)"), { t("Descargar o restaurar JSON por Wi-Fi", "Download or restore JSON over Wi-Fi") }, chevron = true) { host.openLanTransfer() },
        Row.Act(t("Asistente de configuración", "Setup wizard"), chevron = true) { panel.push(wizard()) },
        Row.Act(t("Configuración sin PC", "PC-free setup"), { if (cl.deco.shelf.service.SelfSetup.needed(ctx)) t("Permisos o botones pendientes", "Permissions or buttons pending") else t("Todo concedido", "All granted") }, chevron = true) { panel.push(setupPage()) },
        Row.Section(t("Acerca de Lienzo", "About Lienzo")),
        Row.Info(t("Versión", "Version")) { version },
        Row.Info(t("Créditos", "Credits")) { t("Obras de dominio público vía Wikimedia Commons; feriados Nager.Date; watchdog y ADB local: Free My TV y OmarchyTV (MIT)", "Public-domain artworks via Wikimedia Commons; holidays by Nager.Date; watchdog & local ADB inspired by Free My TV & OmarchyTV (MIT)") },
        Row.Info(t("Clima", "Weather")) { "Weather data by Open-Meteo.com, licencia CC BY 4.0" },
    ))

    // ---- Mis fondos ----

    private val sourceNames get() = listOf(
        t("Colección curada", "Curated collection"),
        t("Mis fondos", "My backgrounds"),
        t("Ambas", "Both"),
        t("Color dinámico", "Dynamic color"),
    )
    private fun sourceName() = sourceNames[p.bgSource.coerceIn(0, sourceNames.size - 1)]

    private fun counts(i: Int, v: Int) = "$i ${if (i == 1) t("imagen", "image") else t("imágenes", "images")}, $v ${if (v == 1) t("video", "video") else t("videos", "videos")}"

    private fun short(n: String, max: Int = 28) = if (n.length <= max) n else n.take(max - 1) + "…"

    private var pendingDelete: String? = null

    fun myBackgroundsPage(): Page {
        val ua = Graph.from(ctx).userArt
        return Page(t("Mis fondos", "My backgrounds"), listOf(
            Row.Section(t("Importar", "Import")),
            Row.Act(t("Importar imágenes y videos", "Import images & videos"), { t("USB o almacenamiento del equipo", "USB or internal storage") }, chevron = true) { host.importFromUsb() },
            Row.Act(t("Enviar desde el celular (QR)", "Send from phone (QR)"), { t("Subir fondos o videos por Wi-Fi", "Upload wallpapers or videos over Wi-Fi") }, chevron = true) { host.openLanTransfer() },
            Row.Act(t("Gestionar mis fondos", "Manage my backgrounds"), {
                val n = allCollections().size
                counts(ua.entries().size, VideoLibrary.all(ctx).size) + if (n > 0) " · $n ${if (n == 1) t("colección", "collection") else t("colecciones", "collections")}" else ""
            }, chevron = true) { pendingDelete = null; panel.push(managePage()) },
            Row.Section(t("Uso", "Usage")),
            Row.Info(t("Uso", "Source")) { t("La fuente del fondo (obras, Mis fondos, ambas o color dinámico) se elige en Pantalla y fondos.", "Choose the background source (artworks, My backgrounds, both, or dynamic color) in Display & backgrounds.") },
            Row.Info("Video") { t("El video del modo arte y del protector se elige en Modo arte y protector.", "Choose the ambient video for Art Mode and Screensaver in Art mode & screensaver.") },
            Row.Section(t("Cómo preparar los archivos", "Preparing files")),
            Row.Info(t("Colecciones", "Collections")) { t("Cada carpeta es una colección: lo que copies en Pictures/Lienzo-Warhammer 40k queda en \"Warhammer 40k\". Se elige en Pantalla y fondos.", "Each folder is a collection: files under Pictures/Lienzo-Name appear under \"Name\" in Display & backgrounds.") },
            Row.Info(t("Imágenes", "Images")) { t("JPG, PNG o WebP en cualquier carpeta. Se ajustan a 1920x1080 (recorte centrado; las verticales llevan passe-partout y solo salen en el protector).", "JPG, PNG, or WebP in any folder. Scaled to 1920x1080 (center crop; portrait photos get a gallery mat in the screensaver).") },
            Row.Info(t("Videos", "Videos")) { t("MP4, MOV, M4V o MKV con H.264 o H.265, hasta 1920x1080 y 60 fps. 4K u otros formatos pueden afectar la reproducción de Netflix y se rechazan.", "MP4, MOV, M4V or MKV (H.264/H.265), up to 1920x1080 @ 60 fps. 4K or other codecs are rejected to protect video decoders.") },
            Row.Info(t("Espacio", "Storage")) { t("Lienzo deja siempre al menos 500 MB libres en el equipo.", "Lienzo always preserves at least 500 MB free on the device.") },
        ))
    }

    /** Tras importar: reconstruye la página Mis fondos para que las opciones de video y los conteos queden al día. */
    fun refreshFondos() {
        if (panel.isOpen && panel.currentTitle() == t("Mis fondos", "My backgrounds")) { panel.back(); panel.push(myBackgroundsPage()) }
    }

    private fun managePage(): Page {
        val ua = Graph.from(ctx).userArt
        val allImgs = ua.entries()
        val allVids = VideoLibrary.all(ctx)
        val rows = ArrayList<Row>()
        if (allImgs.isEmpty() && allVids.isEmpty()) rows += Row.Info("") { t("Todavía no importaste nada. Usa Importar imágenes y videos.", "Nothing imported yet. Use Import images & videos.") }
        fun confirmable(key: String, label: String, size: Long, delete: () -> Unit) = Row.Act(short(label), { if (pendingDelete == key) t("OK de nuevo para borrar", "Press OK again to delete") else SpaceRules.format(size) }) {
            if (pendingDelete == key) {
                pendingDelete = null
                io.execute {
                    delete()
                    main.post {
                        if (!panel.isOpen) return@post
                        host.fondosChanged()
                        // Se reconstruyen también las opciones de "Mis fondos" (lista de videos y conteos)
                        panel.back(); panel.back(); panel.push(myBackgroundsPage()); panel.push(managePage())
                    }
                }
            } else pendingDelete = key
        }
        // Una sección por colección: primero "Borrar colección" y luego sus imágenes y videos
        for (col in allCollections()) {
            val imgs = allImgs.filter { it.collection == col }
            val vids = allVids.filter { it.collection == col }
            rows += Row.Section("$col (${counts(imgs.size, vids.size)})")
            rows += confirmable("c:$col", t("Borrar colección completa", "Delete whole collection"), imgs.sumOf { it.bytes } + vids.sumOf { it.file.length() }) {
                ua.removeCollection(col)
                VideoLibrary.removeCollection(ctx, col)
                if (p.collection == col) p.collection = ""
            }
            imgs.forEach { e -> rows += confirmable("i:${e.id}", e.title + if (e.home) "" else t(" (vertical)", " (portrait)"), e.bytes) { ua.remove(e.id) } }
            vids.forEach { v ->
                rows += confirmable("v:${v.name}", "Video: " + v.file.name, v.file.length()) {
                    VideoLibrary.delete(ctx, v.name)
                    if (p.videoChoice == VideoPolicy.userChoice(v.name)) p.videoChoice = VideoPolicy.MONET
                }
            }
        }
        rows += Row.Info("") { t("OK sobre un elemento lo marca; OK de nuevo lo borra.", "Press OK on an item to arm delete; OK again to confirm.") }
        return Page(t("Gestionar mis fondos", "Manage my backgrounds"), rows)
    }

    // ---- Asistente de configuración ----

    private fun isHome(): Boolean {
        val ri = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    private fun openActivity(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    private fun artState(): Pair<Boolean, String> {
        val (d, tCnt) = try { Graph.from(ctx).art.downloadProgress() } catch (_: Throwable) { 0 to 0 }
        return when {
            tCnt == 0 -> true to t("Listo", "Ready")
            d >= tCnt -> true to t("Listo: $d obras", "Ready: $d artworks")
            else -> false to t("$d de $tCnt obras", "$d of $tCnt artworks")
        }
    }

    fun wizard(): Page = Page(t("Bienvenido a Lienzo", "Welcome to Lienzo"), listOf(
        Row.Info("") { t("Lienzo es tu pantalla de inicio: una obra de arte de fondo, tus apps favoritas en el dock y lo que estabas viendo.", "Lienzo is your home screen: curated fine art behind your favorite dock apps and Watch Next channels.") },
        Row.Info("") { t("Este asistente tarda un minuto. Puedes repetirlo desde Ajustes, en Mantenimiento e información.", "This setup takes one minute. You can re-run it anytime from Settings > Maintenance & about.") },
        Row.Act(t("Comenzar", "Get started")) { panel.push(wizardTopShelf()) },
    ))

    private fun wizardTopShelf(): Page = Page(t("Contenido de tus apps", "App content"), listOf(
        Row.Info(t("Permiso de lectura de Top Shelf", "Top Shelf read permission")) { if (ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) t("Concedido", "Granted") else t("Pendiente", "Pending") },
        Row.Info("") { t("Con este permiso Lienzo muestra lo que sigues viendo (Watch Next) de Netflix, YouTube, Disney+ y otras.", "Allows Lienzo to show Watch Next rows from Netflix, YouTube, Disney+, and other apps.") },
        Row.Act(t("Conceder permiso", "Grant permission")) { host.requestTvPermission() },
        Row.Act(t("Siguiente", "Next")) { panel.push(wizardArt()) },
    ))

    /** Un solo refresco pendiente del asistente: rebuild() vuelve a llamar onShow y antes cada tick duplicaba la cadena (ANR). */
    private var wizardTick: Runnable? = null

    private fun wizardArt(): Page {
        val title = t("Colección de arte", "Art collection")
        val (ready, _) = artState()
        fun tick() {
            wizardTick?.let { main.removeCallbacks(it) }
            val r = Runnable {
                wizardTick = null
                // refresco de 1 s solo mientras esta pantalla está visible y la descarga no termina
                if (panel.isOpen && panel.currentTitle() == title && !artState().first) panel.rebuild()
            }
            wizardTick = r
            main.postDelayed(r, 1000)
        }
        val rows = ArrayList<Row>()
        rows += Row.Info(t("Estado", "Status")) { artState().second }
        rows += Row.Bar(t("Descarga", "Download"), { artState().second }, { Graph.from(ctx).art.downloadProgress().let { (d, tot) -> if (tot > 0) d.toFloat() / tot else 1f } })
        // Total real del catálogo (~0,4 MB por obra a 1920x1080)
        val total = try { Graph.from(ctx).art.downloadProgress().second } catch (_: Throwable) { 0 }
        if (!ready) rows += Row.Act(if (total > 0) t("Descargar $total obras (~${(total * 0.4).toInt()} MB)", "Download $total artworks (~${(total * 0.4).toInt()} MB)") else t("Descargar la colección", "Download collection")) {
            try { Graph.from(ctx).art.startFullDownload() } catch (_: Throwable) {}
            tick()
        }
        rows += Row.Info("") { t("La descarga sigue en segundo plano aunque salgas de esta pantalla.", "Downloads continue in the background even if you leave this screen.") }
        rows += Row.Act(t("Siguiente", "Next")) { panel.push(wizardHome()) }
        return Page(title, rows, onShow = { if (!ready && artState().second != t("Listo", "Ready") && Graph.from(ctx).art.downloadProgress().first > 0) tick() })
    }

    private fun wizardHome(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Info(t("Lienzo como pantalla de inicio", "Lienzo as Home screen")) { if (isHome()) t("Sí, Lienzo ya es tu Home", "Yes, Lienzo is already your Home") else t("No", "No") }
        if (!isHome()) {
            rows += Row.Act(t("Abrir ajustes de Home", "Open Home settings")) {
                if (!openActivity(Intent(Settings.ACTION_HOME_SETTINGS))) panel.push(Page(t("Elegir Lienzo como Home", "Set Lienzo as Home"), listOf(
                    Row.Info("") { t("Pulsa el botón Home del control y, cuando Android pregunte, elige Lienzo y marca \"Siempre\".", "Press Home on your remote and when prompted, pick Lienzo and choose \"Always\".") },
                )))
            }
        }
        rows += Row.Act(t("Siguiente", "Next")) { panel.push(wizardAdb()) }
        return Page(t("Pantalla de inicio", "Home screen"), rows)
    }

    private fun wizardAdb(): Page = Page(t("Funciones avanzadas", "Advanced features"), listOf(
        Row.Info(t("Apagado nocturno", "Night auto-off")) { if (NightMode.hasPermission(ctx)) t("Concedido", "Granted") else "Missing: adb shell pm grant cl.deco.shelf android.permission.WRITE_SECURE_SETTINGS" },
        Row.Info(t("Alarma sobre otras apps", "Alarm overlay")) { if (Settings.canDrawOverlays(ctx)) t("Concedido", "Granted") else "Missing: adb shell appops set cl.deco.shelf SYSTEM_ALERT_WINDOW allow" },
        Row.Info("") { t("Sin ADB todo lo demás funciona; la alarma y el temporizador abren Lienzo a pantalla completa en lugar de aparecer sobre otras apps.", "Without ADB everything else works; alarms & timers open Lienzo fullscreen instead of overlaying active apps.") },
        Row.Act(t("Siguiente", "Next")) { panel.push(wizardEnd()) },
    ))

    private fun wizardEnd(): Page = Page(t("Todo listo", "All set"), listOf(
        Row.Info("") { t("Pulsa MENU sobre una app para fijarla, renombrarla u ocultarla, y abre Ajustes de Lienzo desde la grilla cuando quieras.", "Press MENU on any app to pin, rename, or hide it, and open Lienzo Settings from the grid anytime.") },
        Row.Act(t("Terminar", "Finish")) { host.finishSettings() },
    ))

    private fun storage(): Pair<Long, Long> {
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        return st.availableBytes to st.totalBytes
    }
    private fun storageText(): String {
        val (free, total) = storage()
        fun gb(b: Long) = "%.1f".format(java.util.Locale.US, b / 1073741824.0)
        return "${gb(free)} GB libres de ${gb(total)} GB"
    }
    private fun storageUsed(): Float { val (f, t) = storage(); return if (t > 0) 1f - f.toFloat() / t else 0f }

    // ---- Ubicación y región ----

    private data class City(val name: String, val lat: Double, val lon: Double, val tz: String = "America/Santiago")

    private val cities = listOf(
        City("Arica", -18.4783, -70.3126), City("Iquique", -20.2133, -70.1503), City("Antofagasta", -23.6509, -70.3975),
        City("La Serena", -29.9027, -71.2520), City("Valparaíso", -33.0472, -71.6127), City("Viña del Mar", -33.0245, -71.5518),
        City("Santiago", -33.4489, -70.6693), City("Rancagua", -34.1708, -70.7444), City("Talca", -35.4264, -71.6554),
        City("Concepción", -36.8270, -73.0503), City("Temuco", -38.7359, -72.5904), City("Valdivia", -39.8142, -73.2459),
        City("Puerto Montt", -41.4693, -72.9424), City("Coyhaique", -45.5712, -72.0685),
        City("Punta Arenas", -53.1638, -70.9171, "America/Punta_Arenas"),
    )

    private val countries = listOf(
        "Chile" to "CL", "Argentina" to "AR", "Perú" to "PE", "Colombia" to "CO", "México" to "MX",
        "España" to "ES", "Estados Unidos" to "US",
    )

    private val regions = listOf(
        "Arica y Parinacota" to "CL-AP", "Tarapacá" to "CL-TA", "Antofagasta" to "CL-AN", "Atacama" to "CL-AT",
        "Coquimbo" to "CL-CO", "Valparaíso" to "CL-VS", "Metropolitana" to "CL-RM", "O'Higgins" to "CL-LI",
        "Maule" to "CL-ML", "Ñuble" to "CL-NB", "Biobío" to "CL-BI", "La Araucanía" to "CL-AR",
        "Los Ríos" to "CL-LR", "Los Lagos" to "CL-LL", "Aysén" to "CL-AI", "Magallanes" to "CL-MA",
    )

    private fun countryName(cc: String) = countries.firstOrNull { it.second == cc }?.first ?: cc
    private fun regionName(code: String) = if (code.isEmpty()) t("Solo nacionales", "National only") else regions.firstOrNull { it.second == code }?.first ?: code

    fun locationPage(): Page = Page(t("Ubicación y región", "Location & region"), listOf(
        Row.Section(t("Clima", "Weather")),
        Row.Act(t("Ciudad", "City"), { p.cityName }, chevron = true) { panel.push(cityPage()) },
        choice(t("Unidades", "Units"), ShelfPrefs.FAHRENHEIT, listOf("°C", "°F"), listOf(false, true), { p.fahrenheit }, { p.fahrenheit = it }),
        Row.Section(t("Feriados", "Holidays")),
        Row.Act(t("País", "Country"), { countryName(p.holidaysCountry) }, chevron = true) { panel.push(countryPage()) },
        Row.Act(t("Región (feriados regionales)", "Region (regional holidays)"), { if (p.holidaysCountry == "CL") regionName(p.holidaysRegion) else t("Solo para Chile", "Chile only") }, chevron = true) {
            if (p.holidaysCountry == "CL") panel.push(regionPage())
        },
        Row.Section(t("Fecha", "Date & Language")),
        choice("Idioma / Language", ShelfPrefs.ENGLISH, listOf("Español (Chile)", "English"), listOf(false, true), { p.english }, { p.english = it }),
        choice(t("Primer día de la semana", "First day of week"), ShelfPrefs.SUNDAY_FIRST, listOf(t("Lunes", "Monday"), t("Domingo", "Sunday")), listOf(false, true), { p.sundayFirst }, { p.sundayFirst = it }),
    ))

    private fun cityPage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Act(t("Buscar ciudad…", "Search city…"), chevron = true) { panel.push(searchPage()) }
        rows += Row.Section(t("Ciudades de Chile", "Chilean cities"))
        for (c in cities) rows += Row.Act(c.name, { if (c.name == p.cityName) t("Actual", "Current") else "" }) {
            p.setCity(c.name, c.lat, c.lon, c.tz); host.changed(ShelfPrefs.CITY_NAME); panel.back(); panel.rebuild()
        }
        return Page(t("Ciudad del clima", "Weather city"), rows)
    }

    private fun searchPage(): Page {
        val slot = Row.Slot()
        val status = Row.Slot()
        lateinit var page: Page
        fun search(q: String) {
            if (q.length < 2) return
            panel.fillSlot(status, listOf(Row.Info("") { t("Buscando…", "Searching…") }))
            io.execute {
                val res = try { Geocoding.search(ctx, q) } catch (_: Throwable) { null }
                main.post {
                    if (!panel.isOpen) return@post
                    when {
                        res == null -> { panel.fillSlot(status, listOf(Row.Info("") { t("Sin conexión. Intenta de nuevo.", "No connection. Try again.") })); panel.fillSlot(slot, emptyList()) }
                        res.isEmpty() -> { panel.fillSlot(status, listOf(Row.Info("") { t("Sin resultados para \"$q\"", "No results for \"$q\"") })); panel.fillSlot(slot, emptyList()) }
                        else -> {
                            panel.fillSlot(status, emptyList())
                            panel.fillSlot(slot, res.map { g ->
                                Row.Act(g.label) {
                                    p.setCity(g.name, g.lat, g.lon, g.tz); host.changed(ShelfPrefs.CITY_NAME)
                                    panel.back(); panel.back(); panel.rebuild()
                                }
                            })
                        }
                    }
                }
            }
        }
        page = Page(t("Buscar ciudad", "Search city"), listOf(Row.Input(t("Escribe una ciudad y pulsa Buscar", "Enter a city and press Search"), "", true, ::search), status, slot))
        return page
    }

    private fun countryPage(): Page {
        val rows = ArrayList<Row>()
        for ((n, cc) in countries) rows += Row.Act(n, { if (p.holidaysCountry == cc) t("Actual", "Current") else "" }) { setCountry(cc) }
        rows += Row.Act(t("Otro (código ISO)", "Other (ISO code)"), chevron = true) { panel.push(Page(t("Código de país (ISO 3166-1)", "Country code (ISO 3166-1)"), listOf(Row.Input(t("Ej.: BR, DE, UY", "E.g.: US, BR, DE"), "", false) { code ->
            val cc = code.trim().uppercase()
            if (cc.length == 2 && cc.all { it in 'A'..'Z' }) setCountry(cc)
        }))) }
        return Page(t("País de feriados", "Holidays country"), rows)
    }

    private fun setCountry(cc: String) {
        p.holidaysCountry = cc
        if (cc != "CL") p.holidaysRegion = ""
        host.changed(ShelfPrefs.HOL_CC)
        // vuelve a la página Ubicación y región
        while (panel.back()) { if (panelTitleIsLocation()) break }
        panel.rebuild()
    }

    private fun panelTitleIsLocation() = panel.currentTitle() == t("Ubicación y región", "Location & region")

    private fun regionPage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Act(t("Solo feriados nacionales", "National holidays only"), { if (p.holidaysRegion.isEmpty()) t("Actual", "Current") else "" }) { setRegion("") }
        for ((n, code) in regions) rows += Row.Act(n, { if (p.holidaysRegion == code) t("Actual", "Current") else "" }) { setRegion(code) }
        return Page(t("Región de feriados (Chile)", "Holidays region (Chile)"), rows)
    }

    private fun setRegion(code: String) {
        p.holidaysRegion = code
        host.changed(ShelfPrefs.HOL_REGION)
        panel.back(); panel.rebuild()
    }

    // ---- Respaldo ----

    private fun restorePage(): Page {
        val files = host.backups()
        val rows: List<Row> = if (files.isEmpty()) listOf(Row.Info("") { t("No hay respaldos en Android/data/cl.deco.shelf/files/backup. Copia uno con adb push.", "No backups found in Android/data/cl.deco.shelf/files/backup. Push one via adb or QR upload.") })
        else files.map { f -> Row.Act(f.name) { host.restoreBackup(f) } }
        return Page(t("Restaurar configuración", "Restore settings"), rows)
    }
}
