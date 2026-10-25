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
        Row.Choice(label, listOf(on, off), { if (get()) 0 else 1 }, { set(it == 0); host.changed(key) })

    // Sin emojis: la fuente de color se cargaría solo para esto (varios MB de PSS) y no combina con los íconos del sistema
    // Lienzo primero (es lo que se abre desde "Ajustes de Lienzo") y una sola sección Televisor sin repetidos: antes Imagen,
    // Sonido y Ajustes de Android aparecían dos veces (Televisor y Accesos rápidos)
    fun root(): Page = Page("Ajustes", listOf(
        Row.Section("Lienzo"),
        Row.Act("Pantalla y fondos", { sourceName() }) { panel.push(screenAndBackgroundPage()) },
        Row.Act("Modo arte y protector", { if (p.artEnabled) "Activado" else "Desactivado" }) { panel.push(artAndScreensaverPage()) },
        Row.Act("Barra superior e inicio", { "${if (p.clock24) "24 h" else "12 h"} · ${p.dockMax} en el dock" }) { panel.push(interfacePage()) },
        Row.Act("Apps e íconos", { val n = host.hiddenCount(); if (n == 0) "Ninguna oculta" else "$n ${if (n == 1) "oculta" else "ocultas"}" }) { confirmReset = false; panel.push(appsAndIconsPage()) },
        Row.Act("Control remoto", { if (KeyMap.serviceRunning(ctx)) "Botones activos" else "Requiere activar" }) { panel.push(remotePage()) },
        Row.Act("Sistema y energía", { if (p.nightOn) "Apagado nocturno activo" else "Apagado nocturno inactivo" }) { panel.push(powerAndSystemPage()) },
        Row.Act("Mantenimiento e información", { version }) { panel.push(aboutAndMaintenancePage()) },
    ) + tvRows())

    /** Sección "Televisor": ajustes rápidos del fabricante (si existen) y accesos del sistema, cada uno una sola vez. */
    private fun tvRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows += Row.Section("Televisor")
        QuickAccess.tvQuick(ctx)?.let { quick ->
            rows += Row.Act("Ajustes rápidos", { "Brillo, imagen, sonido, temporizador" }) { host.finishSettings(); openActivity(quick) }
        }
        // Wi-Fi, Bluetooth, Imagen, Sonido, Almacenamiento y, al final, todos los ajustes del sistema
        for (q in QuickAccess.items) {
            val label = if (q === QuickAccess.items.last()) "Todos los ajustes del sistema" else q.label
            rows += Row.Act(label) { QuickAccess.launch(ctx, q) }
        }
        return rows
    }

    /** Botones de apps del control (Netflix, Prime Video, YouTube y los de TCL reasignados) vía el servicio de accesibilidad. */
    private var confirmKeyReset = false

    /** Asignación dinámica de botones (cualquier control): aprender, cambiar acción, app de TV en vivo y restablecer. */
    fun remotePage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Section("Estado")
        rows += Row.Info("Servicio de botones") {
            when {
                KeyMap.serviceRunning(ctx) -> "Activo"
                KeyMap.serviceEnabled(ctx) -> "Habilitado pero detenido por el sistema (en TCL falta el permiso de arranque)"
                else -> "Inactivo"
            }
        }
        if (!KeyMap.serviceRunning(ctx)) {
            rows += Row.Act("Activar sin PC", { "Con la depuración por red del equipo" }) { panel.push(setupPage()) }
            rows += Row.Act("Abrir ajustes de accesibilidad") { openActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        rows += Row.Section("Asignar")
        rows += Row.Act("Asignar un botón", { "Presiona el botón y elige qué hace" }) { panel.push(learnPage()) }
        rows += Row.Act("App de TV en vivo", { KeyMap.liveTvApp(ctx)?.let { KeyMap.appLabel(ctx, it) } ?: "Sin app (función original)" }) { panel.push(liveTvPage()) }
        val map = KeyMap.all(ctx).toList().sortedBy { KeyMap.name(it.first) }
        if (map.isNotEmpty()) {
            rows += Row.Section("Botones asignados")
            for ((code, action) in map) rows += Row.Act(KeyMap.name(code), { KeyMap.actionLabel(ctx, action) }) { panel.push(actionPage(code)) }
        }
        rows += Row.Act("Restablecer valores iniciales", { if (confirmKeyReset) "OK de nuevo para confirmar" else "" }) {
            if (confirmKeyReset) { confirmKeyReset = false; KeyMap.resetDefaults(ctx); panel.replace(remotePage()) } else confirmKeyReset = true
        }
        rows += Row.Info("") { "Flechas, OK, Atrás, Inicio, volumen y encendido no se reasignan. Los botones sin asignar mantienen su función original." }
        return Page("Control remoto", rows, onShow = { KeyMap.stopLearning(); confirmKeyReset = false })
    }

    private fun learnPage(): Page {
        val title = "Asignar un botón"
        return Page(title, listOf(
            Row.Info("") { "Presiona ahora en el control el botón que quieres asignar." },
            Row.Info("") { "Funciona con cualquier control: botones de apps, colores, Guía, Asistente o propios del fabricante." },
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
        rows += Row.Info("Ahora") { KeyMap.actionLabel(ctx, KeyMap.all(ctx)[code]) }
        rows += Row.Section("Acciones")
        rows += Row.Act("Entradas de Lienzo") { pick(KeyMap.INPUTS) }
        rows += Row.Act("Menú de ajustes", { "Lienzo, rápidos o del televisor" }) { pick(KeyMap.SETTINGS) }
        rows += Row.Act("TV en vivo", { KeyMap.liveTvApp(ctx)?.let { KeyMap.appLabel(ctx, it) } ?: "Sin app (función original)" }) { pick(KeyMap.LIVETV) }
        rows += Row.Act("Todas las apps", { "Grilla de Lienzo" }) { pick(KeyMap.APPS) }
        rows += Row.Act("Modo arte", { "Obra a pantalla completa" }) { pick(KeyMap.ART) }
        rows += Row.Act("Función original del sistema") { pick(null) }
        rows += Row.Section("Abrir una app")
        for ((label, pkg) in launchableApps()) rows += Row.Act(label) { pick(KeyMap.app(pkg)) }
        return Page("Botón: ${KeyMap.name(code)}", rows)
    }

    private fun liveTvPage(): Page {
        val rows = ArrayList<Row>()
        rows += Row.Act("Predeterminado", { if (KeyMap.installed(ctx, KeyMap.DEFAULT_LIVETV)) "Movistar TV" else "Sin app (función original)" }) {
            KeyMap.setLiveTvApp(ctx, null); panel.back(); panel.replace(remotePage())
        }
        rows += Row.Section("Apps")
        for ((label, pkg) in launchableApps()) rows += Row.Act(label) { KeyMap.setLiveTvApp(ctx, pkg); panel.back(); panel.replace(remotePage()) }
        rows += Row.Info("") { "Se usa en el botón TV del control y en la opción TV en vivo de Entradas." }
        return Page("App de TV en vivo", rows)
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

    fun screenAndBackgroundPage(): Page = Page("Pantalla y fondos", listOf(
        Row.Section("Fondo"),
        // Fuente: obras de arte, imágenes propias o "Color dinámico" (fondo oscuro con el color de la app enfocada)
        choice("Fuente del fondo", ShelfPrefs.BG_SOURCE, sourceNames, listOf(0, 1, 2, ShelfPrefs.BG_DYNAMIC), { p.bgSource }, { p.bgSource = it }),
        choice("Rotación de obra", ShelfPrefs.BG_INTERVAL, listOf("15 min", "30 min", "60 min", "Diario"), listOf(15, 30, 60, 1440), { p.bgIntervalMin }, { p.bgIntervalMin = it }),
        Row.Act("Cambiar obra ahora") { host.nextArt() },
        Row.Act("Mis fondos", { "Importar y gestionar imágenes y videos propios" }) { panel.push(myBackgroundsPage()) },
        choice("Brillo del fondo", ShelfPrefs.BG_BRIGHTNESS, listOf("60%", "70%", "80%", "90%", "100%"), listOf(60, 70, 80, 90, 100), { p.bgBrightness }, { p.bgBrightness = it }),
        toggle("Atenuar de noche", ShelfPrefs.NIGHT_DIM, "Sí", "No", { p.nightDim }, { p.nightDim = it }),
        choice("Desenfoque en grilla", ShelfPrefs.BLUR_LEVEL, listOf("Ninguno", "Bajo", "Medio", "Alto"), listOf(0, 1, 2, 3), { p.blurLevel }, { p.blurLevel = it }),
        toggle("Colores", ShelfPrefs.DYNAMIC_COLORS, "Dinámicos desde la obra", "Neutros", { p.dynamicColors }, { p.dynamicColors = it }),
    ))

    fun artAndScreensaverPage(): Page = Page("Modo arte y protector", listOf(
        Row.Section("Modo arte"),
        toggle("Modo arte", ShelfPrefs.ART_ON, "Activado", "Desactivado", { p.artEnabled }, { p.artEnabled = it }),
        choice("Espera", ShelfPrefs.ART_WAIT, listOf("30 s", "45 s", "2 min", "5 min"), listOf(30, 45, 120, 300), { p.artWaitSec }, { p.artWaitSec = it }),
        toggle("Usar video", ShelfPrefs.ART_VIDEO, "Sí", "No", { p.artVideo }, { p.artVideo = it }),
        Row.Section("Protector de pantalla"),
        toggle("Usar video", ShelfPrefs.DREAM_VIDEO, "Sí", "No", { p.dreamVideo }, { p.dreamVideo = it }),
        choice("Intervalo de obras", ShelfPrefs.DREAM_INTERVAL, listOf("1 min", "3 min", "5 min"), listOf(1, 3, 5), { p.dreamIntervalMin }, { p.dreamIntervalMin = it }),
    ) + videoRows())

    /**
     * Elección del video (modo arte y protector) junto a "Usar video": antes estaba escondida en Mis fondos y, sin ningún
     * video en el equipo, "Usar video: Sí" no hacía nada sin decirlo.
     */
    private fun videoRows(): List<Row> {
        val (tokens, names) = videoOptions()
        val rows = ArrayList<Row>()
        rows += Row.Section("Video")
        if (tokens.isEmpty()) rows += Row.Info("Sin videos en el equipo") { "Importa uno propio para que el modo arte y el protector muestren video." }
        else rows += Row.Choice("Video", names, { tokens.indexOf(p.videoChoice).coerceAtLeast(0) }, { p.videoChoice = tokens[it]; host.changed(ShelfPrefs.VIDEO_CHOICE) })
        rows += Row.Act("Agregar o quitar videos", { "Mis fondos" }) { panel.push(myBackgroundsPage()) }
        rows += Row.Info("") { "MP4, MOV, M4V o MKV (H.264 o H.265) hasta 1080p. Puedes bajarlo con Downloader al almacenamiento y luego importarlo." }
        return rows
    }

    /** Opciones reales de video: Monet solo si está en el equipo, cada video propio y "Alternar todos" con más de uno. */
    private fun videoOptions(): Pair<List<String>, List<String>> {
        val monet = VideoLibrary.monet(ctx) != null
        val videos = VideoLibrary.userVideos(ctx)
        val tokens = ArrayList<String>(); val names = ArrayList<String>()
        if (monet) { tokens += VideoPolicy.MONET; names += "Monet" }
        for (v in videos) { tokens += VideoPolicy.userChoice(v.name); names += "Propio: " + short(v.name, 22) }
        if (tokens.size > 1) { tokens += VideoPolicy.ALL; names += "Alternar todos" }
        return tokens to names
    }

    fun interfacePage(): Page = Page("Barra superior e inicio", listOf(
        Row.Section("Reloj y barra superior"),
        choice("Reloj", ShelfPrefs.CLOCK24, listOf("24 h", "12 h"), listOf(true, false), { p.clock24 }, { p.clock24 = it }),
        toggle("Clima", ShelfPrefs.WEATHER_ON, "Activado", "Desactivado", { p.weatherOn }, { p.weatherOn = it }),
        Row.Act("Ubicación y región", { p.cityName }) { panel.push(locationPage()) },
        Row.Section("Pantalla de inicio"),
        toggle("Top Shelf", ShelfPrefs.TOP_SHELF, "Activado", "Desactivado", { p.topShelf }, { p.topShelf = it }),
        choice("Favoritas en el dock (máx.)", ShelfPrefs.DOCK_MAX, listOf("4", "5", "6", "7"), listOf(4, 5, 6, 7), { p.dockMax }, { p.dockMax = it }),
    ))

    fun appsAndIconsPage(): Page = Page("Apps e íconos", listOf(
        Row.Section("Íconos"),
        choice("Estilo de íconos", ShelfPrefs.ICON_ORIGINAL, listOf("Adaptado", "Original sin procesar"), listOf(false, true), { p.iconOriginal }, { p.iconOriginal = it }),
        Row.Act("Regenerar íconos") { host.regenerateIcons() },
        Row.Section("Gestión de apps"),
        Row.Act("Restaurar apps ocultas", { val n = host.hiddenCount(); if (n == 0) "Ninguna oculta" else "$n ${if (n == 1) "oculta" else "ocultas"}" }) { host.restoreHidden() },
        Row.Act("Restablecer orden y favoritas", { if (confirmReset) "OK de nuevo para confirmar" else "" }) {
            if (confirmReset) { confirmReset = false; host.resetOrder() } else confirmReset = true
        },
    ))

    fun powerAndSystemPage(): Page = Page("Sistema y energía", listOfNotNull(
        Row.Section("Apagado nocturno"),
        toggle("Apagado por inactividad", ShelfPrefs.NIGHT_ON, "Activado", "Desactivado", { p.nightOn }, { p.nightOn = it }),
        Row.Choice("Desde", halfHours, { (p.nightFromMin / 30).coerceIn(0, 47) }, { p.nightFromMin = it * 30; host.changed(ShelfPrefs.NIGHT_FROM) }),
        Row.Choice("Hasta", halfHours, { (p.nightToMin / 30).coerceIn(0, 47) }, { p.nightToMin = it * 30; host.changed(ShelfPrefs.NIGHT_TO) }),
        choice("Inactividad", ShelfPrefs.NIGHT_IDLE, listOf("30 min", "60 min", "90 min", "120 min"), listOf(30, 60, 90, 120), { p.nightIdleMin }, { p.nightIdleMin = it }),
        Row.Info("Permiso") { if (NightMode.hasPermission(ctx)) "Concedido" else "Falta el permiso de ajustes seguros" },
        if (NightMode.hasPermission(ctx)) null else Row.Act("Conceder sin PC", { "Con la depuración por red del equipo" }) { panel.push(setupPage()) },
    ))

    // ---- Configuración sin PC (ADB local) ----

    private var setupStatus = ""
    private var setupBusy = false
    private var confirmStock = false

    /** Lo que antes pedía adb desde un computador: permisos, servicio de botones, inicio/protector y volver al original. */
    fun setupPage(): Page = Page("Configuración sin PC", listOf(
        Row.Section("Estado"),
        Row.Info("Permisos") {
            val ok = listOf("android.permission.READ_TV_LISTINGS", "android.permission.WRITE_SECURE_SETTINGS")
                .count { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (ok == 2) "Concedidos" else "Faltan ${2 - ok} de 2"
        },
        Row.Info("Servicio de botones") { if (KeyMap.serviceRunning(ctx)) "Activo" else "Inactivo" },
        Row.Info("Resultado") { setupStatus.ifEmpty { "Sin ejecutar" } },
        Row.Section("Acciones"),
        Row.Act("Conceder permisos y activar botones", { "Top Shelf, apagado nocturno, alarma y control" }) {
            runSetup { cl.deco.shelf.service.SelfSetup.permissionsAndKeys(ctx, it) }
        },
        Row.Act("Usar Lienzo como inicio y protector") { runSetup { cl.deco.shelf.service.SelfSetup.makeDefault(ctx, it) } },
        Row.Act("Volver al launcher original", { if (confirmStock) "OK de nuevo para confirmar" else "Lienzo queda instalado" }) {
            if (!confirmStock) { confirmStock = true; panel.rebuild() }
            else { confirmStock = false; runSetup { cl.deco.shelf.service.SelfSetup.restoreStock(ctx, it) } }
        },
        Row.Info("Requisito") { "Depuración por red (o USB) activa en Opciones de desarrollador. La primera vez la TV pregunta \"¿Permitir depuración por USB?\": marca \"Permitir siempre\" y elige Permitir." },
        Row.Info("Seguridad") { "Lienzo solo ejecuta estos comandos fijos. Si no usas la depuración, desactívala al terminar: deja el equipo abierto a la red local." },
    ), onShow = { confirmStock = false })

    /** Ejecuta un paso de SelfSetup en segundo plano y muestra el avance en la fila Resultado. */
    private fun runSetup(task: (() -> Unit) -> String) {
        if (setupBusy) return
        setupBusy = true
        setupStatus = "Conectando…"
        panel.rebuild()
        io.execute {
            val r = task { main.post { setupStatus = "En la TV marca \"Permitir siempre\" y elige Permitir (hasta 60 s)"; panel.rebuild() } }
            main.post { setupBusy = false; setupStatus = r; panel.rebuild() }
        }
    }

    fun aboutAndMaintenancePage(): Page = Page("Mantenimiento e información", listOf(
        // Memoria de video, RAM, tiempo encendido y "Liberar cachés" viven en el panel Salud (antes se repetían aquí)
        Row.Section("Diagnóstico y salud"),
        Row.Act("Salud del equipo", { if (cl.deco.shelf.util.DeviceHealth.read().restartRecommended) "Reinicio recomendado" else "Memoria, encendido y cachés" }) { host.openHealth() },
        Row.Bar("Almacenamiento (/data)", { storageText() }, { storageUsed() }),
        Row.Info("Memoria de Lienzo (PSS)") { "${host.pss()} · objetivo en reposo < 40 MB" },
        Row.Section("Actualizaciones"),
        Row.Act("Buscar actualizaciones", { host.updateStatus() }) { host.checkUpdates() },
        choice("Actualizaciones automáticas", ShelfPrefs.UPDATE_AUTO, listOf("Buscar y avisar", "Desactivadas"), listOf(true, false), { p.updateAuto }, { p.updateAuto = it }),
        Row.Section("Copia de seguridad"),
        Row.Act("Exportar configuración") { lastBackup = host.exportBackup(); panel.rebuild() },
        Row.Info("Respaldo") { lastBackup.ifEmpty { "Carpeta: Android/data/cl.deco.shelf/files/backup (adb pull)" } },
        Row.Act("Restaurar configuración") { panel.push(restorePage()) },
        Row.Act("Asistente de configuración") { panel.push(wizard()) },
        Row.Act("Configuración sin PC", { if (cl.deco.shelf.service.SelfSetup.needed(ctx)) "Permisos o botones pendientes" else "Todo concedido" }) { panel.push(setupPage()) },
        Row.Section("Acerca de Lienzo"),
        Row.Info("Versión") { version },
        Row.Info("Créditos") { "Obras de dominio público vía Wikimedia Commons; feriados Nager.Date; watchdog y ADB local: Free My TV y OmarchyTV (MIT)" },
        Row.Info("Clima") { "Weather data by Open-Meteo.com, licencia CC BY 4.0" },
    ))

    // ---- Mis fondos ----

    private val sourceNames = listOf("Colección curada", "Mis fondos", "Ambas", "Color dinámico")
    private fun sourceName() = sourceNames[p.bgSource.coerceIn(0, sourceNames.size - 1)]

    private fun counts(i: Int, v: Int) = "$i ${if (i == 1) "imagen" else "imágenes"}, $v ${if (v == 1) "video" else "videos"}"

    private fun short(n: String, max: Int = 28) = if (n.length <= max) n else n.take(max - 1) + "…"

    private var pendingDelete: String? = null

    fun myBackgroundsPage(): Page {
        val ua = Graph.from(ctx).userArt
        return Page("Mis fondos", listOf(
            Row.Section("Importar"),
            Row.Act("Importar imágenes y videos", { "USB o almacenamiento del equipo" }) { host.importFromUsb() },
            Row.Act("Gestionar mis fondos", { counts(ua.count(), VideoLibrary.userVideos(ctx).size) }) { pendingDelete = null; panel.push(managePage()) },
            Row.Section("Uso"),
            Row.Info("Uso") { "La fuente del fondo (obras, Mis fondos, ambas o color dinámico) se elige en Pantalla y fondos." },
            Row.Info("Video") { "El video del modo arte y del protector se elige en Modo arte y protector." },
            Row.Section("Cómo preparar los archivos"),
            Row.Info("Imágenes") { "JPG, PNG o WebP en cualquier carpeta. Se ajustan a 1920x1080 (recorte centrado; las verticales llevan passe-partout y solo salen en el protector)." },
            Row.Info("Videos") { "MP4, MOV, M4V o MKV con H.264 o H.265, hasta 1920x1080 y 60 fps. 4K u otros formatos pueden afectar la reproducción de Netflix y se rechazan." },
            Row.Info("Espacio") { "Lienzo deja siempre al menos 500 MB libres en el equipo." },
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
        if (imgs.isEmpty() && vids.isEmpty()) rows += Row.Info("") { "Todavía no importaste nada. Usa Importar imágenes y videos." }
        fun confirmable(key: String, label: String, size: Long, delete: () -> Unit) = Row.Act(short(label), { if (pendingDelete == key) "OK de nuevo para borrar" else SpaceRules.format(size) }) {
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
        Row.Info("") { "Este asistente tarda un minuto. Puedes repetirlo desde Ajustes, en Mantenimiento e información." },
        Row.Act("Comenzar") { panel.push(wizardTopShelf()) },
    ))

    private fun wizardTopShelf(): Page = Page("Contenido de tus apps", listOf(
        Row.Info("Permiso de lectura de Top Shelf") { if (ctx.checkSelfPermission("android.permission.READ_TV_LISTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) "Concedido" else "Pendiente" },
        Row.Info("") { "Con este permiso Lienzo muestra lo que sigues viendo (Watch Next) de Netflix, YouTube, Disney+ y otras." },
        Row.Act("Conceder permiso") { host.requestTvPermission() },
        Row.Act("Siguiente") { panel.push(wizardArt()) },
    ))

    /** Un solo refresco pendiente del asistente: rebuild() vuelve a llamar onShow y antes cada tick duplicaba la cadena (ANR). */
    private var wizardTick: Runnable? = null

    private fun wizardArt(): Page {
        val title = "Colección de arte"
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
        rows += Row.Info("Estado") { artState().second }
        rows += Row.Bar("Descarga", { artState().second }, { Graph.from(ctx).art.downloadProgress().let { (d, t) -> if (t > 0) d.toFloat() / t else 1f } })
        // Total real del catálogo (~0,4 MB por obra a 1920x1080)
        val total = try { Graph.from(ctx).art.downloadProgress().second } catch (_: Throwable) { 0 }
        if (!ready) rows += Row.Act(if (total > 0) "Descargar $total obras (~${(total * 0.4).toInt()} MB)" else "Descargar la colección") {
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
