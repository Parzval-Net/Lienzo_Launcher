package cl.deco.shelf.service

/**
 * Reglas puras y conservadoras para proteger servicios esenciales (TV, entradas, IME, sistema, Google Play)
 * al deshabilitar bloatware o cerrar apps en segundo plano por ADB local.
 */
object DebloatRules {

    val KNOWN_STOCK_LAUNCHERS = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.amazon.tv.launcher",
        "com.mitv.tvhome",
        "com.tcl.cyberui",
        "com.tcl.home",
        "com.hisense.tv.launcher",
        "org.droidtv.nettvbrowser",
        "com.skyworth.tvlauncher",
    )

    /** Apps de streaming que suelen venir preinstaladas en /system o /product (TCL Google TV) y retienen RAM/CMA en reposo. */
    val KNOWN_SYSTEM_STREAMING_APPS = setOf(
        "com.google.android.youtube.tv",
        "com.google.android.youtube.tvkids",
        "com.google.android.youtube.tv.recommendations",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "tv.pluto.android",
        "com.plexapp.android",
        "com.spotify.tv.android",
        "tv.twitch.android.app",
        "com.apple.atve.androidtv.appletv",
    )

    val SAFE_BLOAT = setOf(
        "com.tcl.messagebox",
        "com.tcl.shopdemo",
        "com.tcl.esticker",
        "com.tcl.usercenter",
        "com.tcl.tvweishi",
        "com.tcl.bi",
        "com.tcl.logkit",
        "com.tcl.waterfall.overseas",
        "com.tcl.channelplus",
        "com.tcl.overseasappshow",
        "com.tcl.tv.tclhome_passive",
        "com.tcl.partnercustomizer",
        "com.tcl.ocean.instructions",
        "com.tcl.t_solo",
        "tv.wuaki.apptv",
        "au.com.stan.and",
        "com.google.android.tungsten.setupwraith",
        "com.google.android.partnersetup",
        "com.google.android.videos",
        "com.google.android.music",
        "com.google.android.play.games",
        "com.anoki.acr",
        "com.hisense.store",
        "com.hisense.electronica.manual",
        "com.xiaomi.mitv.shopdemo",
    )

    private val PROTECTED_EXACT = setOf(
        "android",
        "cl.deco.shelf",
        "com.android.systemui",
        "com.android.tv.settings",
        "com.android.settings",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.tv",
        "com.android.providers.tv",
        "com.android.providers.settings",
        "com.android.providers.downloads",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.tv",
        "com.tcl.tv",
        "com.tcl.tvinput",
        "com.tcl.settings",
        "com.tcl.system.server",
        "com.tcl.tcl_bt_rcu_service",
        "com.tcl.autopair",
        "com.tvos",
        "com.google.android.katniss",
        "com.google.android.tts",
        "com.google.android.ext.services",
        "com.google.android.webview",
        "com.google.android.tvrecommendations",
    )

    private val PROTECTED_PREFIXES = listOf(
        "com.android.internal.",
        "com.android.inputmethod.",
        "com.google.android.inputmethod.",
        "com.android.tv.input",
        "com.tcl.tv.input",
        "com.mediatek.tvinput",
        "com.droidlogic.tvinput",
    )

    fun isProtected(pkg: String, extraProtected: Set<String> = emptySet()): Boolean {
        val p = pkg.trim()
        if (p.isEmpty() || p in PROTECTED_EXACT || p in extraProtected) return true
        return PROTECTED_PREFIXES.any { p.startsWith(it) }
    }

    /** Subconjunto de [installed] candidato a `pm disable-user`, excluyendo protegidos. */
    fun filterDebloatTargets(installed: Collection<String>, extraProtected: Set<String> = emptySet()): List<String> =
        installed.asSequence()
            .map { it.trim() }
            .filter { it in SAFE_BLOAT && !isProtected(it, extraProtected) }
            .distinct()
            .sorted()
            .toList()

    /** Apps de usuario en ejecución seguras para `am force-stop` (sin sistema, IME, accesibilidad, TV inputs ni Lienzo). */
    fun filterForceStopTargets(running: Collection<String>, extraProtected: Set<String> = emptySet()): List<String> =
        running.asSequence()
            .map { it.trim() }
            .filter { p ->
                p.isNotEmpty() &&
                    !isProtected(p, extraProtected) &&
                    p !in KNOWN_STOCK_LAUNCHERS &&
                    !p.startsWith("com.android.") &&
                    !p.contains("tvinput") &&
                    !p.contains("hdmi") &&
                    !p.contains("cec")
            }
            .distinct()
            .sorted()
            .toList()
}
