package cl.deco.shelf.dream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import cl.deco.shelf.settings.ShelfPrefs
import java.io.File
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cl.deco.shelf.data.art.Artwork
import cl.deco.shelf.Graph
import cl.deco.shelf.data.art.ArtSource
import cl.deco.shelf.data.art.VideoLibrary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Protector de pantalla estilo Art Mode. Ciclo: video Monet en loop completo (MediaPlayer sobre SurfaceView, sin audio,
 * decodificación por hardware) y 3 obras a pantalla completa con passe-partout (crossfade, intervalo en ajustes),
 * y repite. Sin video (archivo ausente o ajuste desactivado) solo obras.
 */
class ArtDreamService : DreamService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var art: ArtSource? = null

    private lateinit var root: FrameLayout
    private lateinit var imgA: ImageView
    private lateinit var imgB: ImageView
    private lateinit var caption: TextView
    private lateinit var clock: TextView
    private var front: ImageView? = null
    private var frontBmp: Bitmap? = null
    private var backBmp: Bitmap? = null
    private var attached = false
    private val prefs by lazy { ShelfPrefs(this) }
    private var player: MediaPlayer? = null
    private var surface: SurfaceView? = null
    private var worksShown = 0
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val nextRunnable = Runnable {
        if (worksShown >= WORKS_PER_CYCLE && hasVideo()) startVideo() else loadNext()
    }
    private val hideCaption = Runnable { caption.animate().alpha(0f).setDuration(600).start() }
    private val tick = object : Runnable {
        override fun run() {
            clock.text = timeFmt.format(Date())
            val ms = System.currentTimeMillis()
            main.postDelayed(this, 60_000 - ms % 60_000 + 50)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        attached = true
        setContentView(buildViews(this))
        art = Graph.from(this).art // curada, Mis fondos o ambas según ajustes
        tick.run()
        io.execute { runCatching { art?.prefetch(6) }.onFailure { Log.w(TAG, "prefetch: $it") } }
        if (hasVideo()) startVideo() else loadNext()
    }

    // Al despertar se suelta el decodificador antes de que la app siguiente reproduzca.
    override fun onDreamingStopped() {
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        attached = false
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        io.shutdownNow()
        if (::imgA.isInitialized) { imgA.setImageDrawable(null); imgB.setImageDrawable(null) } // puede desacoplarse sin haber armado las vistas
        frontBmp?.recycle(); backBmp?.recycle()
        frontBmp = null; backBmp = null
        super.onDetachedFromWindow()
    }

    private fun buildViews(c: Context): View {
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)
        root = FrameLayout(c).apply { setBackgroundColor(Color.BLACK) }
        imgA = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        imgB = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        root.addView(imgA, FrameLayout.LayoutParams(-1, -1))
        root.addView(imgB, FrameLayout.LayoutParams(-1, -1))
        // Degradado oscuro de base si no hay obras (queda bajo las imágenes)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B1F2A.toInt(), 0xFF05060A.toInt())
        )
        clock = TextView(c).apply {
            setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        root.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(32f).toInt(); bottomMargin = dp(24f).toInt()
        })
        caption = TextView(c).apply {
            setTextColor(0xEEFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.END; alpha = 0f; setLineSpacing(0f, 1.15f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(32f).toInt(); bottomMargin = dp(24f).toInt()
        })
        return root
    }

    /** Hay video elegible (Monet o propio, según ajustes) y el protector lo usa. No avanza la rotación de "Alternar todos". */
    private fun hasVideo(): Boolean = prefs.dreamVideo && VideoLibrary.hasVideo(this, prefs)

    /** Video de esta pasada: el de Monet, un video propio o el siguiente de la rotación. */
    private fun videoFile(): File? = if (prefs.dreamVideo) VideoLibrary.pick(this, prefs) else null

    private fun startVideo() {
        if (!attached || player != null) return
        val file = videoFile()
        if (file == null) { loadNext(); return }
        worksShown = 0
        main.removeCallbacks(nextRunnable)
        val sv = SurfaceView(this)
        surface = sv
        root.addView(sv, 0, FrameLayout.LayoutParams(-1, -1))
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (!attached || player != null) return
                val mp = MediaPlayer()
                player = mp
                try {
                    mp.setDataSource(file.path)
                    mp.setSurface(h.surface)
                    mp.setVolume(0f, 0f)
                    mp.isLooping = false
                    mp.setOnPreparedListener { it.start() }
                    // Primer frame real: recién ahí se funde la obra saliente
                    mp.setOnInfoListener { _, what, _ ->
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) revealVideo()
                        false
                    }
                    mp.setOnCompletionListener { if (attached) loadNext() }
                    mp.setOnErrorListener { _, w, e ->
                        Log.w(TAG, "video error $w/$e")
                        releasePlayer(); worksShown = 0; if (attached) loadNext(); true
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.w(TAG, "video: $e")
                    releasePlayer(); if (attached) loadNext()
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun revealVideo() {
        val out = front ?: return
        out.animate().alpha(0f).setDuration(FADE_MS).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                out.animate().setListener(null)
                out.setImageDrawable(null)
                frontBmp?.recycle(); frontBmp = null
                if (front === out) front = null
            }
        }).start()
        caption.animate().alpha(0f).setDuration(400).start()
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        surface?.let { runCatching { root.removeView(it) } }
        surface = null
    }

    private fun loadNext() {
        if (!attached) return
        val src = art ?: return
        try {
            io.execute {
                val artwork = runCatching { src.next(forHome = false) }.getOrNull()
                val bmp = artwork?.let { runCatching { compose(it) }.onFailure { e -> Log.w(TAG, "decode: $e") }.getOrNull() }
                main.post { if (attached) show(artwork, bmp) }
            }
        } catch (_: Exception) { /* executor cerrado */ }
    }

    /** Decodifica a <= 1920x1080, calcula color promedio y compone fondo + passe-partout + obra. */
    private fun compose(a: Artwork): Pair<Bitmap, Int> {
        val w = 1920; val h = 1080
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(a.file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(a.file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: error("decode null")
        // Color promedio con 1x1 escalado
        val px = Bitmap.createScaledBitmap(src, 1, 1, true)
        val avg = px.getPixel(0, 0); if (px !== src) px.recycle()
        val bg = Color.rgb((Color.red(avg) * 0.45f).toInt(), (Color.green(avg) * 0.45f).toInt(), (Color.blue(avg) * 0.45f).toInt())

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val cv = Canvas(out)
        cv.drawColor(bg)
        val mat = h * 0.06f
        val outer = h * 0.05f
        // Área de la obra: fit centrado dentro del margen + mat
        val availW = w - 2 * (outer + mat); val availH = h - 2 * (outer + mat)
        val s = minOf(availW / src.width, availH / src.height)
        val dw = src.width * s; val dh = src.height * s
        val l = (w - dw) / 2f; val t = (h - dh) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // Mat blanco roto
        val matRect = RectF(l - mat, t - mat, l + dw + mat, t + dh + mat)
        cv.drawRect(matRect, Paint().apply {
            color = 0xFFEFEBE2.toInt()
            setShadowLayer(18f, 0f, 6f, 0x66000000)
        }.also { /* sombra exterior sutil */ })
        cv.drawRect(matRect, Paint().apply { color = 0xFFEFEBE2.toInt() })
        cv.drawBitmap(src, null, RectF(l, t, l + dw, t + dh), paint)
        // Sombra interior sutil del bisel
        val sh = 10f
        val shade = Paint()
        shade.shader = LinearGradient(0f, t, 0f, t + sh, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
        cv.drawRect(l, t, l + dw, t + sh, shade)
        shade.shader = LinearGradient(l, 0f, l + sh, 0f, 0x44000000, 0x00000000, Shader.TileMode.CLAMP)
        cv.drawRect(l, t, l + sh, t + dh, shade)
        src.recycle()
        return out to bg
    }

    private fun show(a: Artwork?, result: Pair<Bitmap, Int>?) {
        if (result == null || a == null) {
            // Sin obras: si el video terminó se repite; si no, degradado + reloj y reintento
            player?.let { runCatching { it.seekTo(0); it.start() } }
            main.postDelayed(nextRunnable, intervalMs() / 3)
            return
        }
        val (bmp, bg) = result
        val incoming = if (front === imgA) imgB else imgA
        val outgoing = front
        val oldIncomingBmp = backBmp
        incoming.setImageBitmap(bmp)
        incoming.scaleX = 1f; incoming.scaleY = 1f
        root.setBackgroundColor(bg)
        incoming.bringToFront(); clock.bringToFront(); caption.bringToFront()
        incoming.animate().alpha(1f).scaleX(1.03f).scaleY(1.03f).setDuration(FADE_MS)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    incoming.animate().setListener(null)
                    if (outgoing != null) {
                        outgoing.alpha = 0f
                        outgoing.setImageDrawable(null)
                        frontBmp?.recycle()
                    }
                    frontBmp = bmp
                    backBmp = null
                    releasePlayer() // la obra ya cubre el video
                }
            }).start()
        oldIncomingBmp?.recycle()
        front = incoming
        backBmp = null
        caption.text = buildString {
            append(a.title).append('\n').append(a.artist)
            if (!a.year.isNullOrBlank()) append(", ").append(a.year)
            append('\n').append(a.museum)
        }
        main.removeCallbacks(hideCaption)
        caption.animate().cancel()
        caption.animate().alpha(1f).setDuration(600).start()
        main.postDelayed(hideCaption, 6_000)
        main.removeCallbacks(nextRunnable)
        worksShown++
        main.postDelayed(nextRunnable, intervalMs())
    }

    private fun intervalMs() = prefs.dreamIntervalMin * 60_000L

    private companion object {
        const val TAG = "Shelf"
        const val WORKS_PER_CYCLE = 3
        const val FADE_MS = 1500L
    }
}
