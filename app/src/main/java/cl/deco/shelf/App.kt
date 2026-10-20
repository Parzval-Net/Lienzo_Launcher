package cl.deco.shelf

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import cl.deco.shelf.guard.CrashLog
import cl.deco.shelf.guard.GuardReceiver
import cl.deco.shelf.data.art.ArtSource
import cl.deco.shelf.data.art.CompositeArt
import cl.deco.shelf.data.art.UserArt
import cl.deco.shelf.data.art.MuseumArt
import cl.deco.shelf.data.holidays.HolidaysRepository
import cl.deco.shelf.data.holidays.HolidaySource
import cl.deco.shelf.data.weather.OpenMeteoWeather
import cl.deco.shelf.data.weather.WeatherSource
import cl.deco.shelf.shelf.TvProviderShelf

/** Application mínima: solo expone el grafo de dependencias, todo lazy. */
class App : Application() {
    val graph: Graph by lazy { Graph(this) }

    override fun onCreate() {
        super.onCreate()
        // Sin I/O en el main thread: solo se instala el handler (una asignación) y, en builds de diagnóstico, StrictMode
        if (BuildConfig.STRICT) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
        }
        if (Application.getProcessName() == packageName) {
            // getSharedPreferences solo dispara la lectura en un hilo propio: cuando MainActivity pide un valor ya está cargado
            getSharedPreferences("shelf", MODE_PRIVATE)
            installCrashHandler()
            if (BuildConfig.CRASH_TRIGGER) registerCrashTrigger()
        }
    }

    /** Registra el crash (timestamp en archivo + aviso al proceso :guard) y deja que el proceso muera como siempre. */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val app = this
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val now = System.currentTimeMillis()
            try {
                CrashLog.record(app, now)
                sendBroadcast(Intent(GuardReceiver.ACTION_CRASHED).setClass(app, GuardReceiver::class.java).putExtra("ts", now))
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e) ?: run { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10) }
        }
    }

    /** Solo builds con -PlienzoCrashTrigger: `am broadcast -a cl.deco.shelf.DEBUG_CRASH -p cl.deco.shelf` provoca un crash. */
    private fun registerCrashTrigger() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { Handler(Looper.getMainLooper()).post { throw RuntimeException("Crash de prueba (debug)") } }
        }
        registerReceiver(r, IntentFilter("cl.deco.shelf.DEBUG_CRASH"), Context.RECEIVER_EXPORTED)
    }
}

/** Fuentes de datos instanciadas bajo demanda (nada se crea hasta que la UI lo pide). */
class Graph(ctx: Context) {
    private val app = ctx.applicationContext
    val weather: WeatherSource by lazy { OpenMeteoWeather(app) }
    val holidays: HolidaySource by lazy { HolidaysRepository(app) }
    val userArt: UserArt by lazy { UserArt(app) }
    /** Fuente del fondo según el ajuste: colección curada, Mis fondos o ambas. */
    val art: ArtSource by lazy { CompositeArt(app, MuseumArt(app), userArt) }
    val shelf: TvProviderShelf by lazy { TvProviderShelf(app) }

    companion object {
        @Volatile private var fallback: Graph? = null

        /** Usa el Graph de App si está registrada en el manifest; si no, uno compartido. */
        fun from(ctx: Context): Graph {
            (ctx.applicationContext as? App)?.let { return it.graph }
            return fallback ?: synchronized(this) {
                fallback ?: Graph(ctx).also { fallback = it }
            }
        }
    }
}
