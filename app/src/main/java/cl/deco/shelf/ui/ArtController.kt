package cl.deco.shelf.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import cl.deco.shelf.Graph
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.BitmapUtil
import cl.deco.shelf.data.art.Artwork
import cl.deco.shelf.data.art.VideoLibrary
import cl.deco.shelf.data.weather.SunDim
import cl.deco.shelf.data.weather.Weather
import cl.deco.shelf.apps.ImageLoader
import cl.deco.shelf.settings.ShelfPrefs
import cl.deco.shelf.util.Blur
import cl.deco.shelf.util.Palette
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Controlador de fondo de arte, desenfoque WebP de la grilla, resplandor dinámico de apps,
 * atenuación solar/nocturna y video ambiente en modo arte.
 */
internal class ArtController(
    private val ctx: Context,
    private val main: Handler,
    private val ioArt: ExecutorService,
    private val ioShelf: ExecutorService,
    private val graph: Graph,
    private val prefs: ShelfPrefs,
    private val loader: ImageLoader,
    private val rootView: FrameLayout,
    private val content: FrameLayout,
    private val artView: ImageView,
    private val blurView: ImageView,
    screenH: Int,
    private val cb: Callbacks,
) {

    interface Callbacks {
        fun isStarted(): Boolean
        fun isGrid(): Boolean
        fun isArtMode(): Boolean
        fun weather(): Weather?
        fun onEnterArtMode()
        fun onExitArtMode()
        fun onThemeDirty()
        fun onPollArtProgress()
    }

    // "Color dinámico": fondo casi negro con un resplandor ambiental que toma el color de la app enfocada
    private val dynBase = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF0E1016.toInt(), 0xFF07080B.toInt()),
    )
    private val dynGlow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = screenH * 1.25f
        setGradientCenter(0.32f, 0.78f)
        colors = intArrayOf(Theme.withAlpha(Theme.NEUTRAL_ACCENT, 0x66), Theme.withAlpha(Theme.NEUTRAL_ACCENT, 0))
    }
    private var glowColor = Theme.NEUTRAL_ACCENT
    private var glowAnim: ValueAnimator? = null
    /** Color dominante por app (k-means sobre su tile, una vez): mover el foco no recalcula nada. */
    private val appColors = HashMap<String, Int>()
    private var glowTarget: String? = null

    private var artBmp: Bitmap? = null
    private var blurBmp: Bitmap? = null
    private var artId: String? = null
    private var palette: Palette? = null
    private var currentArt: Artwork? = null
    private var lastArt = 0L

    private var night = false
    private var dimFactor = -1f
    private var dimAnim: ValueAnimator? = null
    private val sunRun = Runnable { scheduleSun() }

    private var artPlayer: MediaPlayer? = null
    private var artSurface: SurfaceView? = null
    private var pendingFree: Runnable? = null

    private val lastBgFile get() = File(ctx.filesDir, "lastbg.jpg")
    private val lastBlurFile get() = File(ctx.filesDir, "lastblur.webp")

    private val artRetry = Runnable { if (cb.isStarted() && artBmp == null) loadArt(true) }
    private val artVideoRun = Runnable { startArtVideo() }
    private val fadeArtOut: Runnable = object : Runnable {
        override fun run() {
            main.removeCallbacks(fadeArtOut)
            if (artPlayer != null && cb.isArtMode()) artView.animate().alpha(0f).setDuration(1200).start()
        }
    }

    val isNight: Boolean get() = night
    val hasArt: Boolean get() = artBmp != null
    val hasVideoPlayer: Boolean get() = artPlayer != null

    fun onStart(stopAt: Long, awayMs: Long = AWAY_MS) {
        scheduleSun()
        // También rota si el intervalo venció estando fuera: así el tick inmediato no decodifica una segunda obra
        val rotate = (currentArt == null && !lastArtFresh()) ||
            (stopAt > 0 && System.currentTimeMillis() - stopAt > awayMs) ||
            (lastArt > 0 && System.currentTimeMillis() - lastArt >= prefs.bgIntervalMin * 60_000L)
        if (rotate) lastArt = System.currentTimeMillis()
        if (!restoreLastArt(skipFullLoad = !rotate)) loadArt(rotate)
    }

    fun onStop() {
        main.removeCallbacks(artVideoRun)
        main.removeCallbacks(fadeArtOut)
        main.removeCallbacks(sunRun)
        main.removeCallbacks(artRetry)
        stopArtVideo(animate = false)
        pendingFree?.run()
        releaseArt()
    }

    fun onTrimMemoryUiHidden() {
        blurView.setImageDrawable(null)
        blurBmp?.recycle()
        blurBmp = null
    }

    fun onMinuteTick(now: Long) {
        if (now - lastArt >= prefs.bgIntervalMin * 60_000L) {
            lastArt = now
            loadArt(true)
        }
    }

    fun nextArtNow() {
        lastArt = System.currentTimeMillis()
        loadArt(true)
    }

    fun ensureBlurForGrid(): Boolean {
        if (prefs.blurLevel <= 0) return false
        if (blurBmp == null) rebuildBlur() // se soltó con "Liberar cachés de Lienzo"
        return blurBmp != null
    }

    fun scheduleArtVideo() {
        main.removeCallbacks(artVideoRun)
        main.postDelayed(artVideoRun, ART_VIDEO_DELAY_MS)
    }

    fun onLeaveArtMode(animate: Boolean) {
        main.removeCallbacks(artVideoRun)
        stopArtVideo(animate)
    }

    /** Activa el fondo dinámico: suelta la obra (menos memoria) y pinta el fondo oscuro con resplandor. */
    fun showDynamicBackground() {
        artView.animate().cancel()
        releaseArt()
        currentArt = null
        artView.alpha = 1f
        rootView.background = LayerDrawable(arrayOf(dynBase, dynGlow))
        Theme.animateTo(Theme.neutral(), 400L, true)
    }

    /** Lleva el resplandor al color de la app enfocada con una transición de 450 ms (sin capas extra: un solo drawable). */
    fun glowFor(e: AppEntry) {
        if (!prefs.dynamicBg) return
        glowTarget = e.key
        appColors[e.key]?.let { animateGlow(it); return }
        val tile = loader.cached(e) ?: return
        ioShelf.execute {
            val c = try { Palette.from(tile).accent } catch (_: Throwable) { return@execute }
            // Solo si el foco sigue en esa app (al navegar rápido no se pinta el color de una app ya dejada atrás)
            main.post {
                appColors[e.key] = c
                if (cb.isStarted() && prefs.dynamicBg && glowTarget == e.key) animateGlow(c)
            }
        }
    }

    private fun animateGlow(to: Int) {
        if (to == glowColor) return
        glowAnim?.cancel()
        val from = glowColor
        glowColor = to
        val ev = ArgbEvaluator()
        glowAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            interpolator = Ui.DECEL
            addUpdateListener {
                val c = ev.evaluate(it.animatedFraction, from, to) as Int
                dynGlow.colors = intArrayOf(Theme.withAlpha(c, 0x66), Theme.withAlpha(c, 0))
            }
            start()
        }
    }

    private fun loadArt(rotate: Boolean) {
        if (prefs.dynamicBg) { showDynamicBackground(); return } // sin obras: fondo de color dinámico
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
            fun decode(art: Artwork) {
                val tDecode = SystemClock.elapsedRealtime()
                // Blur y paleta salen de la obra limpia; el scrim inferior se hornea después (una capa menos en GPU)
                var tmpBlur: Bitmap? = null
                var tmpPal: Palette? = null
                bmp = try {
                    BitmapUtil.coverFile(art.file.path, 1920, 1080) { b ->
                        tmpBlur = makeBlur(b)
                        tmpPal = try { Palette.from(b) } catch (_: Throwable) { null }
                        bakeScrim(b)
                    }
                } catch (_: Throwable) { null }
                blur = tmpBlur
                pal = tmpPal
                Log.i("Shelf", "loadArt: decodificar, paleta y scrim ${SystemClock.elapsedRealtime() - tDecode} ms (rotar=$rotate)")
            }
            if (a != null) decode(a)
            // La obra vigente ya no existe (p. ej. se borró en Mis fondos): se elige otra en vez de quedar sin fondo
            if (a != null && bmp == null && !rotate) {
                a = try { graph.art.next() } catch (_: Throwable) { null }
                if (a != null) decode(a)
            }
            val art = a
            main.post {
                if (!cb.isStarted() || art == null || bmp == null) {
                    bmp?.recycle()
                    blur?.recycle()
                } else {
                    applyArt(art, bmp!!, blur, pal)
                }
                // Sin ninguna obra local (descarga pausada o primer inicio): un solo reintento pendiente cada 30 s
                if (cb.isStarted() && art == null && artBmp == null) {
                    main.removeCallbacks(artRetry)
                    main.postDelayed(artRetry, 30_000L)
                }
            }
            // repone cache para las próximas rotaciones
            if (art != null) try { graph.art.prefetch(2) } catch (_: Throwable) {}
            main.post { cb.onPollArtProgress() } // si prefetch arrancó la descarga, el chip "Obras n/total" empieza a seguirla
        }
    }

    /** Guarda el fondo ya escalado y horneado, su blur y la paleta para evitar decodificar de nuevo al volver al home. */
    private fun saveLastArt(a: Artwork, bmp: Bitmap, blur: Bitmap?, pal: Palette?) {
        val ed = prefs.sp.edit()
            .putString("last_art_id", a.id)
            .putString("last_art_path", a.file.path)
            .putLong("last_art_ts", System.currentTimeMillis())
            .putInt("last_art_blur_level", prefs.blurLevel)
        if (pal != null) {
            ed.putBoolean("last_art_pal_ok", true)
                .putInt("last_art_pal_a", pal.accent)
                .putInt("last_art_pal_l", pal.light)
                .putInt("last_art_pal_d", pal.dark)
        } else {
            ed.putBoolean("last_art_pal_ok", false)
        }
        ed.apply()
        val copy = try { bmp.copy(Bitmap.Config.RGB_565, false) } catch (_: Throwable) { null } ?: return
        val blurCopy = blur?.let { try { it.copy(Bitmap.Config.RGB_565, false) } catch (_: Throwable) { null } }
        ioArt.execute {
            BitmapUtil.saveJpeg(copy, lastBgFile)
            copy.recycle()
            if (blurCopy != null) {
                BitmapUtil.saveWebp(blurCopy, lastBlurFile, 88)
                blurCopy.recycle()
            } else {
                lastBlurFile.delete()
            }
        }
    }

    /**
     * Arranque en frío y regreso en caliente: pinta el último fondo cacheado (JPEG + blur WebP + paleta) en una sola pasada.
     * Si [skipFullLoad] es true y la caché está completa y vigente, omite re-decodificar la obra completa en 1080p.
     */
    private fun restoreLastArt(skipFullLoad: Boolean = false): Boolean {
        if (artBmp != null || prefs.dynamicBg) return false
        val path = prefs.sp.getString("last_art_path", null) ?: return false
        if ((Theme.season != null) != path.contains("/seasonal/")) return false
        val ts = prefs.sp.getLong("last_art_ts", 0L)
        val f = File(path)
        if (!f.exists() || System.currentTimeMillis() - ts > prefs.bgIntervalMin * 60_000L) return false
        if (!lastBgFile.exists()) return false
        val wantPal = prefs.sp.getBoolean("last_art_pal_ok", false)
        if (skipFullLoad && !wantPal) return false
        if (currentArt == null) {
            currentArt = Artwork(prefs.sp.getString("last_art_id", "last") ?: "last", "", "", null, "", f)
            lastArt = ts
        }
        val wantBlur = prefs.blurLevel
        val cachedBlurLevel = prefs.sp.getInt("last_art_blur_level", -1)
        val cachedPal = if (wantPal) Palette(
            prefs.sp.getInt("last_art_pal_a", Theme.NEUTRAL_ACCENT),
            prefs.sp.getInt("last_art_pal_l", Theme.NEUTRAL_LIGHT),
            prefs.sp.getInt("last_art_pal_d", Theme.NEUTRAL_DARK),
        ) else null
        ioArt.execute {
            val b = BitmapUtil.loadJpeg565(lastBgFile)
            if (b == null) {
                if (skipFullLoad) main.post { if (cb.isStarted() && artBmp == null) loadArt(false) }
                return@execute
            }
            var blur: Bitmap? = null
            if (wantBlur > 0) {
                blur = if (cachedBlurLevel == wantBlur && lastBlurFile.exists()) BitmapUtil.loadJpeg565(lastBlurFile) else null
                if (blur == null) {
                    blur = makeBlur(b)
                    if (blur != null) {
                        val bc = try { blur.copy(Bitmap.Config.RGB_565, false) } catch (_: Throwable) { null }
                        if (bc != null) { BitmapUtil.saveWebp(bc, lastBlurFile, 88); bc.recycle() }
                        prefs.sp.edit().putInt("last_art_blur_level", wantBlur).apply()
                    }
                }
            }
            main.post {
                if (artBmp != null || !cb.isStarted()) { b.recycle(); blur?.recycle(); return@post }
                artBmp = b
                artView.setImageBitmap(b)
                artView.alpha = 1f
                rootView.background = null
                artId = currentArt?.id
                if (cachedPal != null) {
                    palette = cachedPal
                    applyPalette(animate = false)
                }
                val oldBlur = blurBmp
                blurBmp = blur
                blurView.setImageBitmap(blur)
                if (oldBlur != null && oldBlur !== blur) oldBlur.recycle()
            }
        }
        return true
    }

    private fun bakeScrim(b: Bitmap) {
        val h = b.height.toFloat()
        val cv = Canvas(b)
        cv.drawRect(0f, 0f, b.width.toFloat(), h, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0, 0, 0x99000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        // Halos oscuros en las esquinas superiores (detrás del reloj y del clima), horneados en el fondo
        val r = b.width * 380f / 1920f
        for (cx in floatArrayOf(b.width * 300f / 1920f, b.width * 1740f / 1920f)) {
            cv.drawCircle(cx, h * 80f / 1080f, r, Paint().apply {
                shader = RadialGradient(cx, h * 80f / 1080f, r, 0x4D000000, 0, Shader.TileMode.CLAMP)
            })
        }
    }

    /** Blur precalculado una vez: escala directo a 120x67, aplica stack blur y desatura en RGB_565. */
    private fun makeBlur(bmp: Bitmap): Bitmap? = if (prefs.blurLevel == 0) null else try {
        val radius = BLUR_RADII[prefs.blurLevel.coerceIn(0, BLUR_RADII.lastIndex)]
        Blur.blurredDesaturated(bmp, 120, 67, radius, 0.55f)
    } catch (_: Throwable) { null }

    fun clearBlur() {
        // Ninguno: se suelta el bitmap blur y su caché en disco
        blurView.setImageDrawable(null)
        blurBmp?.recycle()
        blurBmp = null
        prefs.sp.edit().putInt("last_art_blur_level", 0).apply()
        ioArt.execute { lastBlurFile.delete() }
    }

    /** Cambió el nivel de desenfoque: recalcula solo el blur a partir de la obra actual y actualiza la caché en disco. */
    fun rebuildBlur() {
        val src = artBmp ?: return
        val level = prefs.blurLevel
        // artBmp se recicla en ioArt (ver recycleArt): este hilo único serializa la lectura y el reciclaje, sin use-after-free
        ioArt.execute {
            val b = try { if (src.isRecycled) null else makeBlur(src) } catch (_: Throwable) { null }
            if (b != null) {
                val bc = try { b.copy(Bitmap.Config.RGB_565, false) } catch (_: Throwable) { null }
                if (bc != null) { BitmapUtil.saveWebp(bc, lastBlurFile, 88); bc.recycle() }
                prefs.sp.edit().putInt("last_art_blur_level", level).apply()
            }
            main.post {
                // Blur de una obra que ya cambió: se descarta en vez de pisar el de la nueva
                if (b == null || !cb.isStarted() || artBmp !== src) { b?.recycle(); return@post }
                val old = blurBmp
                blurBmp = b
                blurView.setImageBitmap(b)
                if (old != null) main.postDelayed({ old.recycle() }, 400)
                if (cb.isGrid()) blurView.animate().alpha(1f).setDuration(200).start() // sin cortar la subida de la grilla
            }
        }
    }

    private fun applyArt(a: Artwork, bmp: Bitmap, blur: Bitmap?, pal: Palette? = null) {
        currentArt = a
        palette = pal
        // Solo una obra nueva (o sin caché completa) se persiste como último fondo
        if (a.id != prefs.sp.getString("last_art_id", null) || !prefs.sp.getBoolean("last_art_pal_ok", false)) {
            saveLastArt(a, bmp, blur, pal)
        }
        applyPalette(animate = artBmp != null)
        val old = artBmp
        val sameArt = old != null && a.id == artId // misma obra (JPEG del regreso al home -> decodificación completa)
        val newD = BitmapDrawable(ctx.resources, bmp)
        artBmp = bmp
        artId = a.id
        if (sameArt) {
            // Es la misma imagen: se reemplaza sin fundido, así volver de una app no se ve como una recarga del fondo
            artView.setImageDrawable(newD)
            recycleArt(old!!)
        } else if (old == null) {
            artView.setImageDrawable(newD)
            if (artPlayer == null) {
                // Primera obra: crossfade desde el degradado; después se retira la capa de fondo (la obra opaca cubre todo)
                artView.animate().cancel()
                artView.alpha = 0f
                artView.animate().alpha(1f).setDuration(700).setInterpolator(Ui.DECEL).start()
                main.postDelayed({ if (artBmp != null && artPlayer == null) rootView.background = null }, 900)
            }
        } else {
            val td = TransitionDrawable(arrayOf(BitmapDrawable(ctx.resources, old), newD))
            artView.setImageDrawable(td)
            td.startTransition(350)
            main.postDelayed({
                artView.setImageDrawable(newD)
                recycleArt(old)
            }, 400)
        }
        val oldBlur = blurBmp
        blurBmp = blur
        blurView.setImageBitmap(blur)
        if (oldBlur != null && oldBlur !== blur) main.postDelayed({ oldBlur.recycle() }, 400)
    }

    /** Paleta dinámica de la obra o neutra, según el ajuste. */
    fun applyPalette(animate: Boolean) {
        val p = if (prefs.dynamicColors && Theme.season == null) palette else null
        Theme.animateTo(p ?: Theme.neutral(), 400L, animate)
    }

    private fun lastArtFresh(): Boolean {
        val path = prefs.sp.getString("last_art_path", null) ?: return false
        if ((Theme.season != null) != path.contains("/seasonal/")) return false // entra o sale de temporada: fondo nuevo
        return File(path).exists() && System.currentTimeMillis() - prefs.sp.getLong("last_art_ts", 0L) <= prefs.bgIntervalMin * 60_000L
    }

    private fun releaseArt() {
        artView.setImageDrawable(null)
        blurView.setImageDrawable(null)
        artBmp?.let { recycleArt(it) }; artBmp = null
        blurBmp?.recycle(); blurBmp = null
        artId = null
    }

    /** La obra se recicla en ioArt: si un rebuildBlur la está leyendo, el reciclaje espera a que termine (hilo único). */
    private fun recycleArt(b: Bitmap) {
        try { ioArt.execute { b.recycle() } } catch (_: RejectedExecutionException) { b.recycle() }
    }

    // ---------- Brillo del fondo y atenuación nocturna ----------

    private fun dimTarget() = prefs.bgBrightness / 100f * (if (night && prefs.nightDim) NIGHT_FACTOR else 1f)

    /** Multiplica el color de la obra (y del blur) por el factor de brillo; con 100% no hay filtro y no cuesta nada. */
    private fun setDimFactor(f: Float) {
        dimFactor = f
        val c = (f * 255f).toInt().coerceIn(0, 255)
        val filter = if (f >= 0.999f) null else LightingColorFilter(Color.rgb(c, c, c), 0)
        artView.colorFilter = filter
        blurView.colorFilter = filter
    }

    fun applyDim(animate: Boolean) {
        val to = dimTarget()
        dimAnim?.cancel()
        if (!animate || dimFactor < 0f || !cb.isStarted()) { setDimFactor(to); return }
        dimAnim = ValueAnimator.ofFloat(dimFactor, to).apply {
            duration = 2500L
            addUpdateListener { setDimFactor(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * Día/noche según el atardecer y el amanecer de Open-Meteo (los mismos datos del clima). Una sola alarma calculada
     * (postDelayed hasta el próximo evento solar), sin ciclos periódicos; la transición es suave solo al cruzar la hora.
     */
    fun scheduleSun() {
        main.removeCallbacks(sunRun)
        val st = cb.weather()?.let { SunDim.state(it.days, System.currentTimeMillis()) } ?: return
        val changed = st.night != night
        night = st.night
        Theme.night = night && prefs.nightDim
        cb.onThemeDirty()
        if (changed || dimFactor < 0f) applyDim(animate = changed)
        st.nextChange?.let { main.postDelayed(sunRun, (it - System.currentTimeMillis()).coerceAtLeast(1000L) + 1000L) }
    }

    // ---------- Modo arte y video ambiente ----------

    /**
     * Botón de Modo arte (Perfil en TCL). Corta: entra con la obra o, ya dentro, pasa a la siguiente (si había video vuelve
     * a la obra). Mantenida: entra directo al video; si el video ya corre, vuelve a la obra. Elegir una obra a mano
     * reinicia la espera del video automático para que no la tape enseguida.
     */
    fun artButton(video: Boolean) {
        if (!cb.isArtMode()) {
            cb.onEnterArtMode()
            if (video) { main.removeCallbacks(artVideoRun); startArtVideo(force = true) }
            return
        }
        main.removeCallbacks(artVideoRun)
        if (video) {
            if (artPlayer != null || artSurface != null) stopArtVideo(true) else startArtVideo(force = true)
            return
        }
        stopArtVideo(true)
        nextArtNow()
        if (prefs.artVideo) main.postDelayed(artVideoRun, ART_VIDEO_DELAY_MS)
    }

    /** Prioridad a la reproducción: suelta el decodificador del modo arte de inmediato, sin animación. */
    fun releaseVideoForPlayback() {
        cb.onExitArtMode()
        stopArtVideo(animate = false)
    }

    /**
     * Video detrás del fondo: SurfaceView en el índice 0 de content (la obra queda encima) y MediaPlayer en loop sin audio.
     * Al primer frame real se funde la obra de alpha 1 a 0 en 1200 ms. Sin video o con el ajuste apagado, el modo arte
     * queda como siempre.
     */
    fun startArtVideo(force: Boolean = false) {
        if (!cb.isArtMode() || artPlayer != null || artSurface != null || !cb.isStarted() || !(prefs.artVideo || force)) return
        val file = VideoLibrary.pick(ctx, prefs) ?: return // Monet, video propio o rotación de todos (ver ajustes: Mis fondos)
        val sv = SurfaceView(ctx)
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

    /** Vuelve la obra a alpha 1 (200 ms) y recién entonces detiene, libera y quita el SurfaceView. */
    fun stopArtVideo(animate: Boolean) {
        main.removeCallbacks(fadeArtOut)
        pendingFree?.let { main.removeCallbacks(it); it.run() }
        val mp = artPlayer
        val sv = artSurface
        artPlayer = null
        artSurface = null
        if (mp == null && sv == null) return
        artView.animate().cancel()
        val free = object : Runnable {
            override fun run() {
                pendingFree = null
                if (mp != null) { runCatching { mp.stop() }; runCatching { mp.release() } }
                if (sv != null) runCatching { content.removeView(sv) }
            }
        }
        // La liberación va por timer (no por withEndAction): applyState cancela las animaciones de artView
        pendingFree = free
        if (animate && cb.isStarted()) {
            artView.animate().alpha(1f).setDuration(200).start()
            main.postDelayed(free, 260)
        } else {
            artView.alpha = 1f
            free.run()
        }
    }

    companion object {
        val BLUR_RADII = intArrayOf(0, 10, 20, 32)
        const val ART_VIDEO_DELAY_MS = 45_000L
        const val NIGHT_FACTOR = 0.8f // 20% adicional de atenuación sobre el ajuste del usuario
        const val AWAY_MS = 10L * 60_000L
    }
}
