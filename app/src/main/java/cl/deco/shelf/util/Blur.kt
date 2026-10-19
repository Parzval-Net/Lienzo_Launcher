package cl.deco.shelf.util

import android.graphics.Bitmap

/** Stub: lo implementa el agente Datos. */
object Blur {
    fun blurred(src: Bitmap, radius: Int = 20, downscale: Int = 8): Bitmap =
        Bitmap.createScaledBitmap(src, maxOf(1, src.width / downscale), maxOf(1, src.height / downscale), true)
}
