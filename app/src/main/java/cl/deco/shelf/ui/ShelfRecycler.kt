package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView que dibuja la celda enfocada encima de sus vecinas (el foco escala sobre los tiles contiguos).
 */
class ShelfRecycler(ctx: Context) : RecyclerView(ctx) {

    init {
        setChildDrawingOrderCallback { count, i ->
            val f = focusedChild
            val idx = if (f == null) -1 else indexOfChild(f)
            when {
                idx < 0 -> i
                i == count - 1 -> idx
                i >= idx -> i + 1
                else -> i
            }
        }
    }
}
