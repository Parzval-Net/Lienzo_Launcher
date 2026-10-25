package cl.deco.shelf.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import org.json.JSONObject

/**
 * Asignación dinámica de botones del control: keycode -> acción, guardada en las prefs ("keymap", JSON) y editable en
 * Ajustes > Control remoto (modo aprender). Sirve para cualquier control: los códigos de fábrica de abajo son solo el valor
 * inicial.
 *
 * Valores iniciales verificados en una TCL (Android 11, control BT_RC813_MA) el 25-10-2026: el firmware entrega keycodes
 * propios sobre 4000 (4062 Netflix, 4099 Prime Video, 4065 YouTube, 4020 TCL Channel, 4140 Guard, 4159 Web, 4137 TCL Home,
 * 4077 Ajustes, 4001 TV en vivo, 4514 Menú ≡, 4056 123) y estándar para Entradas (178), Asistente (219), Información (165),
 * Perfil (288) y CH (166/167). Menú ≡ queda con su función de fábrica (el panel de ajustes rápidos de TCL, que el sistema
 * abre antes del filtro de accesibilidad). Los keycodes estándar de Android cubren otros controles.
 */
object KeyMap {
    /** Acciones: "app:<paquete>", INPUTS, SETTINGS, LIVETV o NONE (deja pasar la tecla al sistema). */
    const val INPUTS = "inputs"
    const val SETTINGS = "settings"
    const val LIVETV = "livetv"
    /** Lienzo con la grilla de todas las apps abierta. */
    const val APPS = "apps"
    /** Modo arte de Lienzo ahora (obra a pantalla completa, con video si está configurado). */
    const val ART = "art"
    const val NONE = "none"
    fun app(pkg: String) = "app:$pkg"

    private const val PREF = "keymap"
    private const val PREF_LIVETV = "livetv_app"
    const val DEFAULT_LIVETV = "cl.movistarplay"

    /** Nombres conocidos de botones para mostrar en Ajustes (el resto se muestra como "Botón <código>"). */
    private val names = mapOf(
        4062 to "Netflix", 4099 to "Prime Video", 4065 to "YouTube", 4020 to "TCL Channel", 4140 to "Guard", 4159 to "Web",
        4137 to "TCL Home", 4077 to "Ajustes (TCL)", 4514 to "Menú ≡ / ajustes rápidos (TCL)", 4001 to "TV en vivo (TCL)", 4056 to "123 (TCL)",
        KeyEvent.KEYCODE_PROFILE_SWITCH to "Perfil", KeyEvent.KEYCODE_INFO to "Información", KeyEvent.KEYCODE_CHANNEL_UP to "CH +",
        KeyEvent.KEYCODE_CHANNEL_DOWN to "CH −", KeyEvent.KEYCODE_TV_INPUT to "Entradas", KeyEvent.KEYCODE_SETTINGS to "Ajustes",
        KeyEvent.KEYCODE_TV to "TV en vivo", KeyEvent.KEYCODE_GUIDE to "Guía", KeyEvent.KEYCODE_ASSIST to "Asistente",
        KeyEvent.KEYCODE_SEARCH to "Buscar", KeyEvent.KEYCODE_VOICE_ASSIST to "Asistente de voz", KeyEvent.KEYCODE_ALL_APPS to "Todas las apps",
        KeyEvent.KEYCODE_EXPLORER to "Navegador", KeyEvent.KEYCODE_PROG_RED to "Rojo", KeyEvent.KEYCODE_PROG_GREEN to "Verde",
        KeyEvent.KEYCODE_PROG_YELLOW to "Amarillo", KeyEvent.KEYCODE_PROG_BLUE to "Azul", KeyEvent.KEYCODE_BOOKMARK to "Favoritos",
    )

    private val defaults: Map<Int, String> = mapOf(
        4062 to app("com.netflix.ninja"),
        4099 to app("com.amazon.amazonvideo.livingroom"),
        4065 to app("com.google.android.youtube.tv"),
        4020 to app("com.arkiv.player.light"),
        4140 to app("com.esaba.downloader"),
        4159 to app("com.instantbits.cast.receiver"),
        4137 to app("com.android.vending"),
        4077 to SETTINGS, // botón con engranaje del control TCL (scan 233), verificado en la TV
        4001 to LIVETV,   // botón TV en vivo (televisor con antena) del control TCL, verificado en la TV
        4056 to APPS,     // botón 123 del control TCL: sin teclado numérico útil en Lienzo, abre todas las apps
        KeyEvent.KEYCODE_PROFILE_SWITCH to ART, // Perfil: el selector de perfiles de Google TV no está; modo arte
        KeyEvent.KEYCODE_SETTINGS to SETTINGS,
        KeyEvent.KEYCODE_TV_INPUT to INPUTS,
        KeyEvent.KEYCODE_TV to LIVETV,
    )

    /** Teclas que nunca se reasignan: navegación, volumen y encendido deben seguir funcionando siempre. */
    private val reserved = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_ESCAPE,
    )

    fun reservedKey(code: Int) = code in reserved

    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    // Solo se lee para teclas no reservadas (las flechas y OK nunca llegan aquí). El JSON se parsea una vez y se reusa mientras
    // el texto guardado no cambie: SharedPreferences devuelve el mismo String, así la comparación cuesta nada, y un respaldo
    // restaurado se aplica sin reiniciar el servicio.
    fun all(ctx: Context): Map<Int, String> = load(ctx)

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedMap: Map<Int, String> = emptyMap()

    private fun load(ctx: Context): Map<Int, String> {
        val raw = sp(ctx).getString(PREF, null) ?: return defaults
        if (raw == cachedRaw) return cachedMap
        val m = try {
            val o = JSONObject(raw)
            val saved = o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to o.getString(k) } }.toMap()
            // Valores iniciales nuevos de versiones posteriores se suman; lo que el usuario cambió (incluido NONE) se respeta
            defaults + saved
        } catch (_: Exception) { defaults }
        cachedMap = m; cachedRaw = raw
        return m
    }

    private fun save(ctx: Context, m: Map<Int, String>) {
        val o = JSONObject(); for ((k, v) in m) o.put(k.toString(), v)
        sp(ctx).edit().putString(PREF, o.toString()).apply()
    }

    /**
     * Acción ejecutable para la tecla, o null para dejarla pasar al sistema: sin asignar, NONE, una app que no está instalada
     * (así un valor inicial de otro equipo no inutiliza el botón) o TV en vivo sin app elegida (el botón TV hace lo suyo).
     */
    fun actionFor(ctx: Context, code: Int): String? {
        val a = all(ctx)[code] ?: return null
        return when {
            a == NONE -> null
            a.startsWith("app:") && !installed(ctx, a.removePrefix("app:")) -> null
            a == LIVETV && liveTvApp(ctx) == null -> null
            else -> a
        }
    }

    /** [action] null = función original: se guarda NONE para que un valor inicial no vuelva a aplicarse al actualizar. */
    fun set(ctx: Context, code: Int, action: String?) {
        val m = all(ctx).toMutableMap()
        m[code] = action ?: NONE
        save(ctx, m)
    }

    fun resetDefaults(ctx: Context) { sp(ctx).edit().remove(PREF).apply() }

    fun name(code: Int) = names[code] ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").takeIf { !it.all(Char::isDigit) }
        ?.let { "Botón $it" } ?: "Botón $code"

    /** App de TV en vivo (botón TV y la opción "TV en vivo" de Entradas): por defecto Movistar TV si está instalada. */
    /** App elegida; si no hay, Movistar TV y luego la app de TV en vivo del fabricante (lista de OmarchyTV, MIT). */
    fun liveTvApp(ctx: Context): String? = sp(ctx).getString(PREF_LIVETV, null)
        ?: (listOf(DEFAULT_LIVETV) + BRAND_LIVETV).firstOrNull { installed(ctx, it) }

    /** Apps de TV en vivo por marca: Sony, Sharp, Google TV, AOSP, DTVKit, TCL, Hisense, Philips y Xiaomi/MediaTek. */
    private val BRAND_LIVETV = listOf(
        "com.sony.dtv.tvx", "jp.co.sharp.av.android.aquostvapp", "com.google.android.tv", "com.android.tv",
        "org.dtvkit.inputsource", "com.tcl.tv", "com.hisense.tv.livetv", "com.philips.tv.tvapp", "com.mediatek.wwtv.tvcenter",
    )

    fun setLiveTvApp(ctx: Context, pkg: String?) { sp(ctx).edit().apply { if (pkg == null) remove(PREF_LIVETV) else putString(PREF_LIVETV, pkg) }.apply() }

    fun actionLabel(ctx: Context, action: String?): String = when {
        action == null || action == NONE -> "Original del sistema"
        action == INPUTS -> "Entradas de Lienzo"
        action == SETTINGS -> "Menú de ajustes"
        action == APPS -> "Todas las apps"
        action == ART -> "Modo arte"
        action == LIVETV -> liveTvApp(ctx)?.let { "TV en vivo: ${appLabel(ctx, it)}" } ?: "Original (sin app de TV en vivo)"
        action.startsWith("app:") -> action.removePrefix("app:").let { if (installed(ctx, it)) appLabel(ctx, it) else "Original (app no instalada)" }
        else -> action
    }

    fun appLabel(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // ---- Modo aprender: Ajustes espera la próxima tecla del control ----

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var learner: ((Int) -> Unit)? = null
    @Volatile private var learnSince = 0L

    fun startLearning(onKey: (Int) -> Unit) { learner = onKey; learnSince = android.os.SystemClock.elapsedRealtime() }
    fun stopLearning() { learner = null }

    /** KeyService: entrega la tecla al modo aprender si está activo (expira a los 30 s). Devuelve true si la consumió. */
    fun offerToLearner(e: KeyEvent): Boolean {
        val l = learner ?: return false
        if (android.os.SystemClock.elapsedRealtime() - learnSince > 30_000L) { learner = null; return false }
        if (reservedKey(e.keyCode)) return false
        if (e.action == KeyEvent.ACTION_UP) { learner = null; val c = e.keyCode; main.post { l(c) } }
        return true
    }

    // ---- Ejecución ----

    /** Ejecuta [action] para una tecla soltada. [long]: pulsación mantenida (en Modo arte, el video). */
    fun run(ctx: Context, action: String, long: Boolean = false) {
        when {
            action == INPUTS -> openLienzo(ctx, ACTION_OPEN_INPUTS)
            // Menú para elegir: Ajustes de Lienzo, ajustes rápidos del televisor o ajustes completos del sistema
            action == SETTINGS -> openLienzo(ctx, ACTION_OPEN_SETTINGS)
            action == APPS -> openLienzo(ctx, ACTION_OPEN_APPS)
            // Corta: entra con una obra o, ya en modo arte, pasa a la siguiente. Mantenida: video (o vuelve a la obra)
            action == ART -> {
                val i = Intent(ACTION_ART_MODE).setClassName(ctx, "cl.deco.shelf.ui.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_ART_VIDEO, long)
                foreground?.invoke(i) ?: start(ctx, i)
            }
            action == LIVETV -> liveTvApp(ctx)?.let { launch(ctx, it) }
            action.startsWith("app:") -> launch(ctx, action.removePrefix("app:"))
        }
    }

    const val ACTION_OPEN_INPUTS = "cl.deco.shelf.OPEN_INPUTS"
    const val ACTION_OPEN_SETTINGS = "cl.deco.shelf.OPEN_SETTINGS"
    const val ACTION_OPEN_APPS = "cl.deco.shelf.OPEN_APPS"
    const val ACTION_ART_MODE = "cl.deco.shelf.ART_MODE"
    const val EXTRA_ART_VIDEO = "art_video"

    /**
     * Lienzo al frente (mismo proceso, hilo principal): recibe la acción directo. Con startActivity Android pausaría la
     * Activity y onPause la sacaría del modo arte, así que la segunda pulsación no podría pasar a la obra siguiente.
     */
    @Volatile var foreground: ((Intent) -> Boolean)? = null
    /** Un HDMI pasó de vacío a cable puesto (HdmiWatcher): Lienzo muestra el aviso para cambiar a esa entrada. */
    const val ACTION_INPUT_CONNECTED = "cl.deco.shelf.INPUT_CONNECTED"
    const val EXTRA_INPUT_ID = "input_id"

    private fun openLienzo(ctx: Context, action: String) =
        start(ctx, Intent(action).setClassName(ctx, "cl.deco.shelf.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

    private fun start(ctx: Context, i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
    catch (e: Exception) { Log.w("KeyService", "no se pudo abrir ${i.action}: $e"); false }

    /** Habilitado en Accesibilidad (lo que elige el usuario). */
    fun serviceEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, KeyService::class.java)
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return list.split(':').any { it.equals(me.flattenToString(), ignoreCase = true) || it.equals(me.flattenToShortString(), ignoreCase = true) }
    }

    /**
     * Enlazado y corriendo. En la TCL un servicio puede quedar habilitado pero sin enlazar: el sistema bloquea el arranque
     * automático (appop APP_AUTO_START) y entonces ningún botón llega a KeyService.
     */
    fun serviceRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return false
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }
    }

    fun installed(ctx: Context, pkg: String) = launchIntent(ctx, pkg) != null

    fun launchIntent(ctx: Context, pkg: String): Intent? =
        ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: ctx.packageManager.getLaunchIntentForPackage(pkg)

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = launchIntent(ctx, pkg)
        if (i == null) {
            Log.w("KeyService", "sin intent de inicio para $pkg")
            Toast.makeText(ctx, "${appLabel(ctx, pkg)} no está instalada", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            true
        } catch (e: Exception) {
            Log.w("KeyService", "no se pudo abrir $pkg: $e")
            false
        }
    }
}
