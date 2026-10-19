package cl.deco.shelf.ui

import android.os.Handler
import android.os.Looper
import cl.deco.shelf.data.weather.Geocoding
import cl.deco.shelf.clock.NightMode
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
        choice("Fuente", ShelfPrefs.BG_ONLINE, listOf("Colección curada", "En línea"), listOf(false, true), { p.bgOnline }, { p.bgOnline = it }),
        choice("Brillo del fondo", ShelfPrefs.BG_BRIGHTNESS, listOf("60%", "70%", "80%", "90%", "100%"), listOf(60, 70, 80, 90, 100), { p.bgBrightness }, { p.bgBrightness = it }),
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
        Row.Section("Apps"),
        Row.Act("Restaurar apps ocultas", { "${host.hiddenCount()} ocultas" }) { host.restoreHidden() },
        Row.Act("Restablecer orden y favoritas", { if (confirmReset) "OK de nuevo para confirmar" else "" }) {
            if (confirmReset) { confirmReset = false; host.resetOrder() } else confirmReset = true
        },
        Row.Section("Info"),
        Row.Info("Versión") { version },
        Row.Info("Memoria (PSS actual)") { host.pss() },
        Row.Info("Créditos") { "Obras de dominio público vía Wikimedia Commons; clima Open-Meteo; feriados Nager.Date" },
        Row.Act("Exportar configuración") { lastBackup = host.exportBackup(); panel.rebuild() },
        Row.Info("Respaldo") { lastBackup.ifEmpty { "Carpeta: Android/data/cl.deco.shelf/files/backup (adb pull)" } },
        Row.Act("Restaurar configuración") { panel.push(restorePage()) },
    ))

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
                val res = try { Geocoding.search(q) } catch (_: Throwable) { null }
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
