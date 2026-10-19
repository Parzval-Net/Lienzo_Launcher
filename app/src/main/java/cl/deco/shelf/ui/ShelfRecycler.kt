package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView del dock + grilla. Controla el scroll al enfocar (el dock vuelve al tope; al entrar a la
 * grilla se sube la fila enfocada para que el Top Shelf se colapse) y dibuja el foco encima de sus vecinos.
 */
class ShelfRecycler(ctx: Context) : RecyclerView(ctx) {

    private val d = ctx.resources.displayMetrics.density

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

    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean): Boolean {
        val a = adapter as? AppAdapter ?: return super.requestChildRectangleOnScreen(child, rect, immediate)
        val pos = getChildAdapterPosition(child)
        if (pos < 0 || a.entryAt(pos) == null) return super.requestChildRectangleOnScreen(child, rect, immediate)
        val dy: Int
        if (a.isFavPos(pos)) {
            dy = -computeVerticalScrollOffset()
        } else {
            val top = child.top
            val bottom = child.bottom
            val firstRow = pos - a.gridStart < 5
            dy = if (firstRow || top < 24 * d || bottom > height - 12 * d) (top - 40 * d).toInt() else 0
        }
        if (dy == 0) return false
        if (immediate) scrollBy(0, dy) else smoothScrollBy(0, dy, Ui.DECEL, 260)
        return true
    }
}

/** Panel translúcido redondeado detrás de la fila de favoritas (color sólido con alpha, sin blur). */
class DockPanelDecoration(ctx: Context, private val favCount: () -> Int) : RecyclerView.ItemDecoration() {
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66101018 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x24FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = d
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val n = favCount()
        if (n == 0) return
        var first: View? = null
        var last: View? = null
        var any = false
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            val p = parent.getChildAdapterPosition(v)
            if (p == 0) first = v
            if (p == n - 1) last = v
            if (p in 0 until n) any = true
        }
        if (!any) return
        rect.set(
            parent.paddingLeft - 6 * d,
            first?.top?.toFloat() ?: -600f,
            parent.width - parent.paddingRight + 6 * d,
            last?.bottom?.toFloat() ?: (parent.height + 600f),
        )
        val r = 18 * d
        c.drawRoundRect(rect, r, r, fill)
        c.drawRoundRect(rect, r, r, stroke)
    }
}
