package cl.deco.shelf.settings

import android.content.Context
import android.content.SharedPreferences

/** Ajustes de Shelf en SharedPreferences "shelf" (misma que favoritas). Lecturas baratas, sin cache propia. */
class ShelfPrefs(context: Context) {

    val sp: SharedPreferences = context.applicationContext.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d

    // Fondo
    var bgIntervalMin: Int get() = int(BG_INTERVAL, 30); set(v) = put(BG_INTERVAL, v)
    var bgOnline: Boolean get() = bool(BG_ONLINE, false); set(v) = put(BG_ONLINE, v)
    /** Fuente del fondo: 0 = colección curada (por defecto), 1 = Mis fondos, 2 = ambas. Ver SourcePolicy. */
    var bgSource: Int get() = int(BG_SOURCE, 0).coerceIn(0, 2); set(v) = put(BG_SOURCE, v)
    /** Video del protector y del modo arte: "monet" (por defecto), "user:<archivo>" o "all". Ver VideoPolicy. */
    var videoChoice: String get() = str(VIDEO_CHOICE, "monet"); set(v) = put(VIDEO_CHOICE, v)
    var bgBrightness: Int get() = int(BG_BRIGHT, 100).coerceIn(60, 100); set(v) = put(BG_BRIGHT, v)
    /** 0 = ninguno (por defecto, sin generar el bitmap), 1 = bajo, 2 = medio, 3 = alto. */
    var blurLevel: Int get() = int(BLUR_LEVEL, 0).coerceIn(0, 3); set(v) = put(BLUR_LEVEL, v)
    /** Atenúa el fondo 20% extra entre el atardecer y el amanecer (Open-Meteo). */
    var nightDim: Boolean get() = bool(NIGHT_DIM, true); set(v) = put(NIGHT_DIM, v)
    var dynamicColors: Boolean get() = bool(DYN_COLORS, true); set(v) = put(DYN_COLORS, v)
    // Modo arte del home
    var artEnabled: Boolean get() = bool(ART_ON, true); set(v) = put(ART_ON, v)
    var artWaitSec: Int get() = int(ART_WAIT, 45); set(v) = put(ART_WAIT, v)
    var artVideo: Boolean get() = bool(ART_VIDEO, true); set(v) = put(ART_VIDEO, v)
    // Protector
    var dreamVideo: Boolean get() = bool(DREAM_VIDEO, true); set(v) = put(DREAM_VIDEO, v)
    var dreamIntervalMin: Int get() = int(DREAM_INTERVAL, 3); set(v) = put(DREAM_INTERVAL, v)
    // Top Shelf, reloj, clima, dock
    var topShelf: Boolean get() = bool(TOP_SHELF, true); set(v) = put(TOP_SHELF, v)
    var clock24: Boolean get() = bool(CLOCK24, true); set(v) = put(CLOCK24, v)
    var weatherOn: Boolean get() = bool(WEATHER_ON, true); set(v) = put(WEATHER_ON, v)
    var dockMax: Int get() = int(DOCK_MAX, 7).coerceIn(4, 7); set(v) = put(DOCK_MAX, v)
    /** true = íconos originales sin procesar; false = adaptados (por defecto). */
    var iconOriginal: Boolean get() = bool(ICON_ORIGINAL, false); set(v) = put(ICON_ORIGINAL, v)
    // Apagado nocturno (minutos desde las 00:00)
    var nightOn: Boolean get() = bool(NIGHT_ON, true); set(v) = put(NIGHT_ON, v)
    var nightFromMin: Int get() = int(NIGHT_FROM, 21 * 60); set(v) = put(NIGHT_FROM, v)
    var nightToMin: Int get() = int(NIGHT_TO, 7 * 60); set(v) = put(NIGHT_TO, v)
    var nightIdleMin: Int get() = int(NIGHT_IDLE, 60); set(v) = put(NIGHT_IDLE, v)
    /** El asistente de primer inicio ya se mostró. */
    var setupDone: Boolean get() = bool(SETUP_DONE, false); set(v) = put(SETUP_DONE, v)
    /** "Recordarme más tarde" de Salud del deco: el aviso queda oculto hasta esta hora (ms epoch). */
    var healthSnoozeUntil: Long get() = sp.getLong("health_snooze_until", 0L); set(v) { sp.edit().putLong("health_snooze_until", v).apply() }
    // Ubicación y región
    var cityName: String get() = str(CITY_NAME, "Santiago"); set(v) = put(CITY_NAME, v)
    var cityLat: Double get() = java.lang.Double.longBitsToDouble(sp.getLong(CITY_LAT, java.lang.Double.doubleToLongBits(-33.4489))); set(v) { sp.edit().putLong(CITY_LAT, java.lang.Double.doubleToLongBits(v)).apply() }
    var cityLon: Double get() = java.lang.Double.longBitsToDouble(sp.getLong(CITY_LON, java.lang.Double.doubleToLongBits(-70.6693))); set(v) { sp.edit().putLong(CITY_LON, java.lang.Double.doubleToLongBits(v)).apply() }
    var cityTz: String get() = str(CITY_TZ, "America/Santiago"); set(v) = put(CITY_TZ, v)
    var holidaysCountry: String get() = str(HOL_CC, "CL"); set(v) = put(HOL_CC, v.uppercase())
    /** Código de región ISO (p. ej. CL-AP); vacío = solo feriados nacionales. */
    var holidaysRegion: String get() = str(HOL_REGION, ""); set(v) = put(HOL_REGION, v)
    var fahrenheit: Boolean get() = bool(FAHRENHEIT, false); set(v) = put(FAHRENHEIT, v)
    var english: Boolean get() = bool(ENGLISH, false); set(v) = put(ENGLISH, v)
    var sundayFirst: Boolean get() = bool(SUNDAY_FIRST, false); set(v) = put(SUNDAY_FIRST, v)
    /** Actualizaciones automáticas: true = buscar y avisar (por defecto), false = desactivadas (solo manual desde Info). */
    var updateAuto: Boolean get() = bool(UPDATE_AUTO, true); set(v) = put(UPDATE_AUTO, v)

    fun setCity(name: String, lat: Double, lon: Double, tz: String) {
        sp.edit().putString(CITY_NAME, name)
            .putLong(CITY_LAT, java.lang.Double.doubleToLongBits(lat))
            .putLong(CITY_LON, java.lang.Double.doubleToLongBits(lon))
            .putString(CITY_TZ, tz).apply()
    }

    /** Clave estable de ubicación, para invalidar la cache del clima. */
    fun cityKey() = "%.3f,%.3f,%s".format(java.util.Locale.US, cityLat, cityLon, cityTz)

    /** Borra solo los ajustes de Shelf, no favoritas ni orden. */
    fun resetSettings() {
        val e = sp.edit()
        for (k in ALL) e.remove(k)
        e.apply()
    }

    private fun put(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    private fun put(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    private fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }

    companion object {
        const val BG_BRIGHTNESS = "bg_brightness"; const val BLUR_LEVEL = "blur_level"; const val DYNAMIC_COLORS = "dyn_colors"
        private const val BG_BRIGHT = BG_BRIGHTNESS; private const val DYN_COLORS = DYNAMIC_COLORS
        const val NIGHT_DIM = "night_dim"
        const val BG_INTERVAL = "bg_interval_min"; const val BG_ONLINE = "bg_online"
        const val BG_SOURCE = "bg_source"; const val VIDEO_CHOICE = "video_choice"
        const val ART_ON = "art_enabled"; const val ART_WAIT = "art_wait_s"; const val ART_VIDEO = "art_video"
        const val DREAM_VIDEO = "dream_video"; const val DREAM_INTERVAL = "dream_interval_min"
        const val TOP_SHELF = "top_shelf"; const val CLOCK24 = "clock24"; const val WEATHER_ON = "weather_on"
        const val DOCK_MAX = "dock_max"; const val ICON_ORIGINAL = "icon_original"
        const val SETUP_DONE = "setup_done"
        const val NIGHT_ON = "night_on"; const val NIGHT_FROM = "night_from"; const val NIGHT_TO = "night_to"; const val NIGHT_IDLE = "night_idle"
        const val CITY_NAME = "city_name"; const val CITY_LAT = "city_lat"; const val CITY_LON = "city_lon"; const val CITY_TZ = "city_tz"
        const val HOL_CC = "hol_cc"; const val HOL_REGION = "hol_region"
        const val UPDATE_AUTO = "update_auto"
        const val FAHRENHEIT = "fahrenheit"; const val ENGLISH = "english"; const val SUNDAY_FIRST = "sunday_first"
        private val ALL = listOf(
            BG_INTERVAL, BG_ONLINE, BG_SOURCE, VIDEO_CHOICE, BG_BRIGHT, NIGHT_DIM, BLUR_LEVEL, DYN_COLORS, ART_ON, ART_WAIT, ART_VIDEO, DREAM_VIDEO, DREAM_INTERVAL, TOP_SHELF, CLOCK24,
            WEATHER_ON, DOCK_MAX, ICON_ORIGINAL, CITY_NAME, CITY_LAT, CITY_LON, CITY_TZ, HOL_CC, HOL_REGION,
            FAHRENHEIT, ENGLISH, SUNDAY_FIRST, UPDATE_AUTO, NIGHT_ON, NIGHT_FROM, NIGHT_TO, NIGHT_IDLE,
        )
    }
}
