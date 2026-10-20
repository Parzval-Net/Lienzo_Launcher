package cl.deco.shelf.ui

import android.os.Handler
import android.os.Looper
import cl.deco.shelf.data.weather.Geocoding
import android.content.Intent
import android.provider.Settings
import cl.deco.shelf.Graph
import cl.deco.shelf.clock.NightMode
import cl.deco.shelf.data.art.SpaceRules
import cl.deco.shelf.data.art.VideoLibrary
import cl.deco.shelf.data.art.VideoPolicy
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
    /** Cambió el contenido de Mis fondos (borrado) o la fuente: recargar el fondo si corresponde. */
    fun fondosChanged()
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
        Row.Choice(label, listOf(on, off), { if (get()) 0 else 1 }, { set(it == 0); host.changed(key) })

    fun root(): Page = Page("Ajustes de Lienzo", listOf(
        Row.Section("Fondo"),
        choice("Rotación de obra", ShelfPrefs.BG_INTERVAL, listOf("15 min", "30 min", "60 min", "Diario"), listOf(15, 30, 60, 1440), { p.bgIntervalMin }, { p.bgIntervalMin = it }),
        Row.Act("Cambiar obra ahora") { host.nextArt() },
        Row.Act("Mis fondos", { sourceName() }) { panel.push(myBackgroundsPage()) },
        choice("Brillo del fondo", ShelfPrefs.BG_BRIGHTNESS, listOf("60%", "70%", "80%", "90%", "100%"), listOf(60, 70, 80, 90, 100), { p.bgBrightness }, { p.bgBrightness = it }),
        toggle("Atenuar de noche", ShelfPrefs.NIGHT_DIM, "Sí", "No", { p.nightDim }, { p.nightDim = it }),
        choice("Desenfoque en grilla", ShelfPrefs.BLUR_LEVEL, listOf("Ninguno", "Bajo", "Medio", "Alto"), listOf(0, 1, 2, 3), { p.blurLevel }, { p.blurLevel = it }),
        toggle("Colores", ShelfPrefs.DYNAMIC_COLORS, "Dinámicos desde la obra", "Neutros", { p.dynamicColors }, { p.dynamicColors = it }),
        Row.Section("Modo arte"),
        toggle("Modo arte", ShelfPrefs.ART_ON, "Activado", "Desactivado", { p.artEnabled }, { p.artEnabled = it }),
        choice("Espera", ShelfPrefs.ART_WAIT, listOf("30 s", "45 s", "2 min", "5 min"), listOf(30, 45, 120, 300), { p.artWaitSec }, { p.artWaitSec = it }),
        toggle("Usar video", ShelfPrefs.ART_VIDEO, "Sí", "No", { p.artVideo }, { p.artVideo = it }),
        Row.Section("Protector de pantalla"),
        toggle("Usar video", ShelfPrefs.DREAM_VIDEO, "Sí", "No", { p.dreamVideo }, { p.dreamVideo = it }),
        choice("Intervalo de obras", ShelfPrefs.DREAM_INTERVAL, listOf("1 min", "3 min", "5 min"), listOf(1, 3, 5), { p.dreamIntervalMin }, { p.dreamIntervalMin = it }),
        Row.Section("Pantalla de inicio"),
        toggle("Top Shelf", ShelfPrefs.TOP_SHELF, "Activado", "Desactivado", { p.topShelf }, { p.topShelf = it }),
        choice("Favoritas en el dock (máx.)", ShelfPrefs.DOCK_MAX, listOf("5", "6", "7", "8"), listOf(5, 6, 7, 8), { p.dockMax }, { p.dockMax = it }),
        choice("Reloj", ShelfPrefs.CLOCK24, listOf("24 h", "12 h"), listOf(true, false), { p.clock24 }, { p.clock24 = it }),
        toggle("Clima", ShelfPrefs.WEATHER_ON, "Activado", "Desactivado", { p.weatherOn }, { p.weatherOn = it }),
        Row.Act("Ubicación y región", { p.cityName }) { panel.push(locationPage()) },
        Row.Section("Apagado nocturno"),
        toggle("Apagado por inactividad", ShelfPrefs.NIGHT_ON, "Activado", "Desactivado", { p.nightOn }, { p.nightOn = it }),
        Row.Choice("Desde", halfHours, { (p.nightFromMin / 30).coerceIn(0, 47) }, { p.nightFromMin = it * 30; host.changed(ShelfPrefs.NIGHT_FROM) }),
        Row.Choice("Hasta", halfHours, { (p.nightToMin / 30).coerceIn(0, 47) }, { p.nightToMin = it * 30; host.changed(ShelfPrefs.NIGHT_TO) }),
        choice("Inactividad", ShelfPrefs.NIGHT_IDLE, listOf("30 min", "60 min", "90 min", "120 min"), listOf(30, 60, 90, 120), { p.nightIdleMin }, { p.nightIdleMin = it }),
        Row.Info("Permiso") { if (NightMode.hasPermission(ctx)) "Concedido" else "Falta: adb shell pm grant cl.deco.shelf android.permission.WRITE_SECURE_SETTINGS" },
        Row.Section("Íconos"),
        choice("Estilo de íconos", ShelfPrefs.ICON_ORIGINAL, listOf("Adaptado", "Original sin procesar"), listOf(false, true), { p.iconOriginal }, { p.iconOriginal = it }),
        Row.Act("Regenerar íconos") { host.regenerateIcons() },
        Row.Section("Accesos rápidos"),
        *QuickAccess.items.map { q -> Row.Act(q.label) { QuickAccess.launch(ctx, q) } }.toTypedArray(),
        Row.Section("Apps"),
        Row.Act("Restaurar apps ocultas", { "${host.hiddenCount()} ocultas" }) { host.restoreHidden() },
        Row.Act("Restablecer orden y favoritas", { if (confirmReset) "OK de nuevo para confirmar" else "" }) {
            if (confirmReset) { confirmReset = false; host.resetOrder() } else confirmReset = true
        },
        Row.Section("Info"),
        Row.Info("Versión") { version },
        Row.Info("Memoria (PSS actual)") { host.pss() },
        Row.Bar("Almacenamiento (/data)", { storageText() }, { storageUsed() }),
        Row.Act("Salud del deco", { if (cl.deco.shelf.util.DeviceHealth.read().restartRecommended) "Reinicio recomendado" else "En orden" }) { host.openHealth() },
        Row.Info("Memoria de video (CMA)") { cl.deco.shelf.util.DeviceHealth.cmaText(cl.deco.shelf.util.DeviceHealth.read()) },
        Row.Info("Encendido hace") { cl.deco.shelf.util.DeviceHealth.uptimeText(cl.deco.shelf.util.DeviceHealth.read().uptimeMs) },
        Row.Info("Créditos") { "Obras de dominio público vía Wikimedia Commons; clima Open-Meteo; feriados Nager.Date; idea del watchdog: Free My TV (MIT)" },
        Row.Act("Exportar configuración") { lastBackup = host.exportBackup(); panel.rebuild() },
        Row.Info("Respaldo") { lastBackup.ifEmpty { "Carpeta: Android/data/cl.deco.shelf/files/backup (adb pull)" } },
        Row.Act("Restaurar configuración") { panel.push(restorePage()) },
        Row.Act("Asistente de configuración") { panel.push(wizard()) },
        Row.Section("Avanzado"),
        Row.Info("Botón Home con accesibilidad") { if (homeServiceOn()) "Activo" else "Apagado (opcional, para decos donde no se puede cambiar el launcher por defecto)" },
        Row.Act("Abrir ajustes de accesibilidad") { openActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    ))

    // ---- Mis fondos ----

    private fun sourceName() = listOf("Colección curada", "Mis fondos", "Ambas")[p.bgSource.coerceIn(0, 2)]

    private fun short(n: String, max: Int = 28) = if (n.length <= max) n else n.take(max - 1) + "…"

    private var pendingDelete: String? = null

    fun myBackgroundsPage(): Page {
        val ua = Graph.from(ctx).userArt
        val videos = VideoLibrary.userVideos(ctx)
        val tokens = listOf(VideoPolicy.MONET) + videos.map { VideoPolicy.userChoice(it.name) } + (if (videos.isNotEmpty()) listOf(VideoPolicy.ALL) else emptyList())
        val names = listOf("Monet") + videos.map { "Propio: " + short(it.name, 22) } + (if (videos.isNotEmpty()) listOf("Alternar todos") else emptyList())
        return Page("Mis fondos", listOf(
            Row.Section("Importar"),
            Row.Act("Importar desde USB", { "Imágenes y videos" }) { host.importFromUsb() },
            Row.Act("Gestionar mis fondos", { "${ua.count()} imágenes, ${VideoLibrary.userVideos(ctx).size} videos" }) { pendingDelete = null; panel.push(managePage()) },
            Row.Section("Uso"),
            choice("Fuente del fondo", ShelfPrefs.BG_SOURCE, listOf("Colección curada", "Mis fondos", "Ambas"), listOf(0, 1, 2), { p.bgSource }, { p.bgSource = it }),
            Row.Choice("Video del protector y del modo arte", names, { tokens.indexOf(p.videoChoice).coerceAtLeast(0) }, { p.videoChoice = tokens[it]; host.changed(ShelfPrefs.VIDEO_CHOICE) }),
            Row.Section("Cómo preparar el pendrive"),
            Row.Info("Imágenes") { "JPG, PNG o WebP en cualquier carpeta. Se ajustan a 1920x1080 (recorte centrado; las verticales llevan passe-partout y solo salen en el protector)." },
            Row.Info("Videos") { "MP4, MOV, M4V o MKV con H.264 o H.265, hasta 1920x1080 y 60 fps. 4K u otros formatos pueden afectar la reproducción de Netflix y se rechazan." },
            Row.Info("Espacio") { "Lienzo deja siempre al menos 500 MB libres en el deco." },
        ))
    }

    /** Tras importar: reconstruye la página Mis fondos para que las opciones de video y los conteos queden al día. */
    fun refreshFondos() {
        if (panel.isOpen && panel.currentTitle() == "Mis fondos") { panel.back(); panel.push(myBackgroundsPage()) }
    }

    private fun managePage(): Page {
        val ua = Graph.from(ctx).userArt
        val imgs = ua.entries()
        val vids = VideoLibrary.userVideos(ctx)
        val rows = ArrayList<Row>()
        if (imgs.isEmpty() && vids.isEmpty()) rows += Row.Info("") { "Todavía no importaste nada. Usa Importar desde USB." }
        fun confirmable(key: String, label: String, size: Long, delete: () -> Unit) = Row.Act(short(label), { if (pendingDelete == key) "OK de nuevo para borrar" else SpaceRules.format(size) }) {
            if (pendingDelete == key) {
                pendingDelete = null
                io.execute {
                    delete()
                    main.post {
                        if (!panel.isOpen) return@post
                        host.fondosChanged()
                        panel.back(); panel.push(managePage())
                    }
                }
            } else pendingDelete = key
        }
        if (imgs.isNotEmpty()) {
            rows += Row.Section("Imágenes (${imgs.size})")
            imgs.forEach { e -> rows += confirmable("i:${e.id}", e.title + if (e.home) "" else " (vertical)", e.bytes) { ua.remove(e.id) } }
        }
        if (vids.isNotEmpty()) {
            rows += Row.Section("Videos (${vids.size})")
            vids.forEach { f ->
                rows += confirmable("v:${f.name}", f.name, f.length()) {
                    VideoLibrary.delete(ctx, f.name)
                    if (p.videoChoice == VideoPolicy.userChoice(f.name)) p.videoChoice = VideoPolicy.MONET
                }
            }
        }
        rows += Row.Info("") { "OK sobre un elemento lo marca; OK de nuevo lo borra." }
        return Page("Gestionar mis fondos", rows)
    }

    // ---- Asistente de configuración ----

    private fun isHome(): Boolean {
        val ri = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    private fun homeServiceOn(): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty().contains("${ctx.packageName}/")

    private fun openActivity(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    private fun artState(): Pair<Boolean, String> {
        val (d, t) = try { Graph.from(ctx).art.downloadProgress() } catch (_: Throwable) { 0 to 0 }
        return when {
            t == 0 -> true to "Listo"
            d >= t -> true to "Listo: $d obras"
            else -> false to "$d de $t obras"
        }
    }

    fun wizard(): Page = Page("Bienvenido a Lienzo", listOf(
        Row.Info("") { "Lienzo es tu pantalla de inicio: una obra de arte de fondo, tus apps favoritas en el dock y lo que estabas viendo." },
        Row.Info("") { "Este asistente tarda un minuto. Puedes repetirlo desde Ajustes, en Info." },
        Row.Act("Comenzar") { panel.push(wizardTopShelf()) },
    ))

    private fun wizardTopShelf(): Page = Page("Contenido de tus apps", listOf(
        Row.Info("Permiso de lectura de Top Shelf") { if (ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) "Concedido" else "Pendiente" },
        Row.Info("") { "Con este permiso Lienzo muestra lo que sigues viendo (Watch Next) de Netflix, YouTube, Disney+ y otras." },
        Row.Act("Conceder permiso") { host.requestTvPermission() },
        Row.Act("Siguiente") { panel.push(wizardArt()) },
    ))

    private fun wizardArt(): Page {
        val title = "Colección de arte"
        val (ready, _) = artState()
        fun tick() {
            main.postDelayed({
                // refresco de 1 s solo mientras esta pantalla está visible y la descarga no termina
                if (panel.isOpen && panel.currentTitle() == title) { panel.rebuild(); if (!artState().first) tick() }
            }, 1000)
        }
        val rows = ArrayList<Row>()
        rows += Row.Info("Estado") { artState().second }
        rows += Row.Bar("Descarga", { artState().second }, { Graph.from(ctx).art.downloadProgress().let { (d, t) -> if (t > 0) d.toFloat() / t else 1f } })
        if (!ready) rows += Row.Act("Descargar 60 obras (~23 MB)") {
            try { Graph.from(ctx).art.startFullDownload() } catch (_: Throwable) {}
            tick()
        }
        rows += Row.Info("") { "La descarga sigue en segundo plano aunque salgas de esta pantalla." }
        rows += Row.Act("Siguiente") { panel.push(wizardHome()) }
        return Page(title, rows, onShow = { if (!ready && artState().second != "Listo" && Graph.from(ctx).art.downloadProgress().first > 0) tick() })
    }

    private fun wizardHome(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Info("Lienzo como pantalla de inicio") { if (isHome()) "Sí, Lienzo ya es tu Home" else "No" }
        if (!isHome()) {
            rows += Row.Act("Abrir ajustes de Home") {
                if (!openActivity(Intent(Settings.ACTION_HOME_SETTINGS))) panel.push(Page("Elegir Lienzo como Home", listOf(
                    Row.Info("") { "Pulsa el botón Home del control y, cuando Android pregunte, elige Lienzo y marca \"Siempre\"." },
                )))
            }
            rows += Row.Info("") { "Si tu deco no deja cambiar el launcher, activa \"Botón Home con accesibilidad\": Lienzo se abrirá al pulsar Home." }
            rows += Row.Act("Abrir ajustes de accesibilidad") { openActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        rows += Row.Act("Siguiente") { panel.push(wizardAdb()) }
        return Page("Pantalla de inicio", rows)
    }

    private fun wizardAdb(): Page = Page("Funciones avanzadas", listOf(
        Row.Info("Apagado nocturno") { if (NightMode.hasPermission(ctx)) "Concedido" else "Falta: adb shell pm grant cl.deco.shelf android.permission.WRITE_SECURE_SETTINGS" },
        Row.Info("Alarma sobre otras apps") { if (Settings.canDrawOverlays(ctx)) "Concedido" else "Falta: adb shell appops set cl.deco.shelf SYSTEM_ALERT_WINDOW allow" },
        Row.Info("") { "Sin ADB todo lo demás funciona; la alarma y el temporizador abren Lienzo a pantalla completa en lugar de aparecer sobre otras apps." },
        Row.Act("Siguiente") { panel.push(wizardEnd()) },
    ))

    private fun wizardEnd(): Page = Page("Todo listo", listOf(
        Row.Info("") { "Pulsa MENU sobre una app para fijarla, renombrarla u ocultarla, y abre Ajustes de Lienzo desde la grilla cuando quieras." },
        Row.Act("Terminar") { host.finishSettings() },
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
    private fun regionName(code: String) = if (code.isEmpty()) "Solo nacionales" else regions.firstOrNull { it.second == code }?.first ?: code

    fun locationPage(): Page = Page("Ubicación y región", listOf(
        Row.Section("Clima"),
        Row.Act("Ciudad", { p.cityName }) { panel.push(cityPage()) },
        choice("Unidades", ShelfPrefs.FAHRENHEIT, listOf("°C", "°F"), listOf(false, true), { p.fahrenheit }, { p.fahrenheit = it }),
        Row.Section("Feriados"),
        Row.Act("País", { countryName(p.holidaysCountry) }) { panel.push(countryPage()) },
        Row.Act("Región (feriados regionales)", { if (p.holidaysCountry == "CL") regionName(p.holidaysRegion) else "Solo para Chile" }) {
            if (p.holidaysCountry == "CL") panel.push(regionPage())
        },
        Row.Section("Fecha"),
        choice("Idioma de la fecha", ShelfPrefs.ENGLISH, listOf("Español (Chile)", "English"), listOf(false, true), { p.english }, { p.english = it }),
        choice("Primer día de la semana", ShelfPrefs.SUNDAY_FIRST, listOf("Lunes", "Domingo"), listOf(false, true), { p.sundayFirst }, { p.sundayFirst = it }),
    ))

    private fun cityPage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Act("Buscar ciudad…") { panel.push(searchPage()) }
        rows += Row.Section("Ciudades de Chile")
        for (c in cities) rows += Row.Act(c.name, { if (c.name == p.cityName) "Actual" else "" }) {
            p.setCity(c.name, c.lat, c.lon, c.tz); host.changed(ShelfPrefs.CITY_NAME); panel.back(); panel.rebuild()
        }
        return Page("Ciudad del clima", rows)
    }

    private fun searchPage(): Page {
        val slot = Row.Slot()
        val status = Row.Slot()
        lateinit var page: Page
        fun search(q: String) {
            if (q.length < 2) return
            panel.fillSlot(status, listOf(Row.Info("") { "Buscando…" }))
            io.execute {
                val res = try { Geocoding.search(ctx, q) } catch (_: Throwable) { null }
                main.post {
                    if (!panel.isOpen) return@post
                    when {
                        res == null -> { panel.fillSlot(status, listOf(Row.Info("") { "Sin conexión. Intenta de nuevo." })); panel.fillSlot(slot, emptyList()) }
                        res.isEmpty() -> { panel.fillSlot(status, listOf(Row.Info("") { "Sin resultados para \"$q\"" })); panel.fillSlot(slot, emptyList()) }
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
        page = Page("Buscar ciudad", listOf(Row.Input("Escribe una ciudad y pulsa Buscar", "", true, ::search), status, slot))
        return page
    }

    private fun countryPage(): Page {
        val rows = ArrayList<Row>()
        for ((n, cc) in countries) rows += Row.Act(n, { if (p.holidaysCountry == cc) "Actual" else "" }) { setCountry(cc) }
        rows += Row.Act("Otro (código ISO)") { panel.push(Page("Código de país (ISO 3166-1)", listOf(Row.Input("Ej.: BR, DE, UY", "", false) { code ->
            val cc = code.trim().uppercase()
            if (cc.length == 2 && cc.all { it in 'A'..'Z' }) setCountry(cc)
        }))) }
        return Page("País de feriados", rows)
    }

    private fun setCountry(cc: String) {
        p.holidaysCountry = cc
        if (cc != "CL") p.holidaysRegion = ""
        host.changed(ShelfPrefs.HOL_CC)
        // vuelve a la página Ubicación y región
        while (panel.back()) { if (panelTitleIsLocation()) break }
        panel.rebuild()
    }

    private fun panelTitleIsLocation() = panel.currentTitle() == "Ubicación y región"

    private fun regionPage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Act("Solo feriados nacionales", { if (p.holidaysRegion.isEmpty()) "Actual" else "" }) { setRegion("") }
        for ((n, code) in regions) rows += Row.Act(n, { if (p.holidaysRegion == code) "Actual" else "" }) { setRegion(code) }
        return Page("Región de feriados (Chile)", rows)
    }

    private fun setRegion(code: String) {
        p.holidaysRegion = code
        host.changed(ShelfPrefs.HOL_REGION)
        panel.back(); panel.rebuild()
    }

    // ---- Respaldo ----

    private fun restorePage(): Page {
        val files = host.backups()
        val rows: List<Row> = if (files.isEmpty()) listOf(Row.Info("") { "No hay respaldos en Android/data/cl.deco.shelf/files/backup. Copia uno con adb push." })
        else files.map { f -> Row.Act(f.name) { host.restoreBackup(f) } }
        return Page("Restaurar configuración", rows)
    }
}
