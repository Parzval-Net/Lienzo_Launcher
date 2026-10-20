package cl.deco.shelf.data.art

import java.util.Locale

/**
 * Reglas puras (testeables en JVM) de "Mis fondos": validación de video, espacio libre, selección de fuente y de video,
 * geometría del recorte y nombres de archivo. Sin dependencias de Android.
 */

/** Validación de videos propios. Límites pensados para no competir con el decodificador de Netflix/Movistar. */
object VideoRules {
    const val MAX_W = 1920
    const val MAX_H = 1080
    /** 60 fps con holgura para 59,94 y 60,00x. */
    const val MAX_FPS = 60.5f
    const val HINT = "4K u otros formatos pueden afectar la reproducción de Netflix."

    enum class Reason { UNREADABLE, CODEC, RESOLUTION, FPS, HDR, NO_DECODER }

    sealed class Verdict {
        object Ok : Verdict()
        data class Rejected(val reason: Reason, val message: String) : Verdict()
    }

    fun isAvc(mime: String?) = mime.equals("video/avc", true)
    fun isHevc(mime: String?) = mime.equals("video/hevc", true)

    /**
     * [rotation] en grados (90/270 intercambian ancho y alto). [fps] <= 0 significa desconocido (se acepta).
     * [decoderAvailable] false si MediaCodecList no ofrece un decodificador para este formato exacto (perfil, 10 bits, etc.).
     */
    fun evaluate(mime: String?, width: Int, height: Int, rotation: Int = 0, fps: Float = 0f, hdr: Boolean = false, decoderAvailable: Boolean = true): Verdict {
        if (mime.isNullOrBlank() || width <= 0 || height <= 0)
            return Verdict.Rejected(Reason.UNREADABLE, "No se pudo leer el video. $HINT")
        if (!isAvc(mime) && !isHevc(mime))
            return Verdict.Rejected(Reason.CODEC, "Códec ${codecName(mime)} no admitido: solo H.264 o H.265. $HINT")
        val swap = rotation.mod(180) == 90
        val w = if (swap) height else width
        val h = if (swap) width else height
        if (w > MAX_W || h > MAX_H)
            return Verdict.Rejected(Reason.RESOLUTION, "Resolución ${w}x$h: el máximo es ${MAX_W}x$MAX_H. $HINT")
        if (fps > MAX_FPS)
            return Verdict.Rejected(Reason.FPS, "${fps.roundToIntText()} fps: el máximo es 60. $HINT")
        if (hdr)
            return Verdict.Rejected(Reason.HDR, "Video HDR: puede cambiar el modo de pantalla del deco. $HINT")
        if (!decoderAvailable)
            return Verdict.Rejected(Reason.NO_DECODER, "El deco no puede decodificar este perfil de video. $HINT")
        return Verdict.Ok
    }

    private fun Float.roundToIntText() = Math.round(this).toString()

    fun codecName(mime: String?): String = when (mime?.lowercase(Locale.ROOT)) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mpeg2" -> "MPEG-2"
        "video/mp4v-es" -> "MPEG-4"
        null, "" -> "desconocido"
        else -> mime.removePrefix("video/")
    }

    /** Extensiones de contenedor que se copian. */
    val EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv")
}

/** Cálculo de espacio: se deja siempre un margen libre en el almacenamiento. */
object SpaceRules {
    const val RESERVE_BYTES = 500L * 1024 * 1024
    /** Peso estimado de una imagen importada (JPEG q85 1920x1080), para mostrar el total antes de copiar. */
    const val EST_IMAGE_BYTES = 600L * 1024

    data class Check(val ok: Boolean, val needed: Long, val free: Long, val shortBy: Long)

    fun check(needed: Long, free: Long, reserve: Long = RESERVE_BYTES): Check {
        val n = needed.coerceAtLeast(0)
        val short = n + reserve - free
        return Check(short <= 0, n, free, short.coerceAtLeast(0))
    }

    fun estimate(imageCount: Int, videoBytes: Long): Long = imageCount.coerceAtLeast(0) * EST_IMAGE_BYTES + videoBytes.coerceAtLeast(0)

    /** Texto de tamaño en español de Chile (coma decimal). */
    fun format(bytes: Long): String {
        val loc = Locale("es", "CL")
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1024L * 1024 -> String.format(loc, "%d KB", (b + 1023) / 1024)
            b < 1024L * 1024 * 1024 -> String.format(loc, "%.1f MB", b / 1048576.0)
            else -> String.format(loc, "%.2f GB", b / 1073741824.0)
        }
    }
}

enum class Src { CURATED, MINE }

/** Fuente del fondo: Colección curada / Mis fondos / Ambas. */
object SourcePolicy {
    const val CURATED = 0
    const val MINE = 1
    const val BOTH = 2

    /**
     * Orden de fuentes a consultar. [mineAvailable]: hay obras propias elegibles (ya filtradas por home=false si es para el
     * fondo). Si "Mis fondos" está vacío siempre cae a la colección curada. [tick] alterna en "Ambas".
     */
    fun order(mode: Int, tick: Int, mineAvailable: Boolean): List<Src> = when {
        mode != MINE && mode != BOTH -> listOf(Src.CURATED)
        !mineAvailable -> listOf(Src.CURATED)
        mode == MINE -> listOf(Src.MINE, Src.CURATED)
        tick.mod(2) == 0 -> listOf(Src.CURATED, Src.MINE)
        else -> listOf(Src.MINE, Src.CURATED)
    }
}

/** Video del protector y del modo arte: Monet / un video propio / alternar todos. */
object VideoPolicy {
    const val MONET = "monet"
    const val ALL = "all"
    const val USER_PREFIX = "user:"

    fun userChoice(name: String) = USER_PREFIX + name

    /**
     * Devuelve MONET o el nombre de un video propio. [tick] solo importa en ALL. Si el video propio elegido ya no existe
     * cae a Monet; sin Monet ni propios devuelve null (el modo arte queda solo con obras).
     */
    fun pick(choice: String, userNames: List<String>, monetAvailable: Boolean, tick: Int): String? {
        val pool = ArrayList<String>()
        if (monetAvailable) pool += MONET
        pool += userNames
        return when {
            choice == ALL -> pool.takeIf { it.isNotEmpty() }?.get(tick.mod(pool.size))
            choice.startsWith(USER_PREFIX) -> choice.removePrefix(USER_PREFIX).takeIf { it in userNames } ?: if (monetAvailable) MONET else null
            else -> if (monetAvailable) MONET else null
        }
    }
}

/** Geometría del recorte y utilidades de importación. */
object ImportMath {
    /** Región de la imagen origen que llena [outW]x[outH] en modo COVER con recorte centrado. */
    fun coverRect(srcW: Int, srcH: Int, outW: Int = ArtCatalog.OUT_W, outH: Int = ArtCatalog.OUT_H): PixelRect {
        val srcAspect = srcW.toDouble() / srcH
        val outAspect = outW.toDouble() / outH
        return if (srcAspect > outAspect) {
            val cw = Math.round(srcH * outAspect).toInt().coerceIn(1, srcW)
            val l = (srcW - cw) / 2
            PixelRect(l, 0, l + cw, srcH)
        } else {
            val ch = Math.round(srcW / outAspect).toInt().coerceIn(1, srcH)
            val t = (srcH - ch) / 2
            PixelRect(0, t, srcW, t + ch)
        }
    }

    /** Mayor potencia de 2 que deja la imagen decodificada con al menos el tamaño de salida en ambos ejes. */
    fun sampleSize(srcW: Int, srcH: Int, outW: Int = ArtCatalog.OUT_W, outH: Int = ArtCatalog.OUT_H): Int {
        var s = 1
        while (srcW / (s * 2) >= outW && srcH / (s * 2) >= outH) s *= 2
        return s
    }

    /** Verticales y cuadradas van con passe-partout y quedan fuera del fondo (home=false), como en la colección curada. */
    fun needsMat(w: Int, h: Int) = h >= w

    /** Grados de rotación a aplicar según la orientación EXIF (1..8); espejo ignorado. */
    fun exifRotation(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        6, 7 -> 90
        5, 8 -> 270
        else -> 0
    }

    /** Nombre seguro y único dentro de [existing] (se compara sin distinguir mayúsculas). */
    fun safeFileName(display: String, existing: Set<String>): String {
        val dot = display.lastIndexOf('.')
        val base0 = if (dot > 0) display.substring(0, dot) else display
        val ext = if (dot > 0) display.substring(dot + 1).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(5) else ""
        val base = base0.map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }.joinToString("").trim().trim('.').take(60).ifEmpty { "video" }
        val taken = existing.map { it.lowercase(Locale.ROOT) }.toSet()
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var n = 1
        var name = base + suffix
        while (name.lowercase(Locale.ROOT) in taken) { n++; name = "$base-$n$suffix" }
        return name
    }

    /** Clave para no importar dos veces el mismo archivo. */
    fun dedupeKey(name: String, size: Long) = "$name|$size"
}
