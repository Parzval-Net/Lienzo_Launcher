package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.ImageLoader
import cl.deco.shelf.shelf.TopShelfItem

/**
 * Zona sobre el dock. Con contenido de TvProvider: carrusel de tarjetas 16:9. Sin contenido: solo el nombre
 * de la app enfocada sobre su tile, dejando la obra limpia. Crossfade de 200 ms.
 */
class TopShelfView(
    ctx: Context,
    private val loader: ImageLoader,
    private val onItem: (TopShelfItem) -> Unit,
) : FrameLayout(ctx) {

    private val cardW = ctx.dp(180)
    private val cardH = ctx.dp(101)
    private val padX = ctx.dp(8)
    private val padY = ctx.dp(8)
    private val radius = ctx.dpf(12f)

    private val items = ArrayList<TopShelfItem>()

    private val carousel = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        setHasFixedSize(true)
        itemAnimator = null
        clipToPadding = false
        clipChildren = false
        setPadding(ctx.dp(65) - padX, 0, ctx.dp(48), 0)
        visibility = INVISIBLE
        alpha = 0f
        isFocusable = false
    }

    // Nombre de la app enfocada (sin contenido de TvProvider), sobre su tile
    private val name = tv(ctx, 34f, Ui.TEXT, "sans-serif-medium").apply {
        maxLines = 1
        setShadowLayer(ctx.dpf(5f), 0f, ctx.dpf(1f), 0xB3000000.toInt())
        visibility = INVISIBLE
        alpha = 0f
    }
    private var showingItems = false

    init {
        clipChildren = false
        carousel.adapter = CardAdapter()
        addView(carousel, LayoutParams(-1, -2, Gravity.TOP))
        addView(name, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = ctx.dp(6) })
    }

    /** Con contenido de TvProvider: carrusel sobre el dock. */
    fun show(list: List<TopShelfItem>) {
        items.clear(); items.addAll(list)
        carousel.adapter?.notifyDataSetChanged()
        carousel.scrollToPosition(0)
        showingItems = true
        fade(name, false)
        fade(carousel, true)
    }

    /** Sin contenido: solo el nombre de la app, alineado sobre su tile ([x] en px, coordenada de pantalla). */
    fun showName(label: String, x: Int) {
        showingItems = false
        name.text = label
        val w = name.paint.measureText(label)
        val maxX = resources.displayMetrics.widthPixels - w - context.dp(48)
        (name.layoutParams as LayoutParams).leftMargin = x.toFloat().coerceAtMost(maxX).toInt().coerceAtLeast(context.dp(48))
        name.requestLayout()
        fade(carousel, false)
        fade(name, true)
    }

    /** Oculta ambos (mientras se espera el debounce o con la grilla abierta). */
    fun clear() {
        showingItems = false
        fade(carousel, false)
        fade(name, false)
    }

    private fun fade(v: View, on: Boolean) {
        v.animate().cancel()
        if (on) {
            v.visibility = VISIBLE
            v.animate().alpha(1f).setDuration(200).start()
        } else if (v.visibility == VISIBLE) {
            v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = INVISIBLE }.start()
        }
    }

    private inner class CardVH(val cell: FocusCell, val title: android.widget.TextView, val sub: android.widget.TextView) :
        RecyclerView.ViewHolder(cell)

    private inner class CardAdapter : RecyclerView.Adapter<CardVH>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val ctx = parent.context
            val cell = FocusCell(ctx, cardW, cardH, padX, padY, padY, radius)
            val t = tv(ctx, 14f, Ui.TEXT, "sans-serif-medium").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val s = tv(ctx, 11f, Ui.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(10))
                addView(t, LinearLayout.LayoutParams(-1, -2))
                addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
            }
            cell.content.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val vh = CardVH(cell, t, s)
            cell.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onItem) }
            return vh
        }

        override fun onBindViewHolder(h: CardVH, position: Int) {
            val it = items[position]
            h.title.text = it.title
            h.sub.text = it.subtitle.orEmpty()
            h.sub.visibility = if (it.subtitle.isNullOrEmpty()) GONE else VISIBLE
            h.cell.tag = it.imageUri
            val uri = it.imageUri
            if (uri == null) {
                h.cell.image.setImageDrawable(placeholder())
                return
            }
            val hit = loader.cachedUri(uri)
            if (hit != null) {
                h.cell.image.setImageBitmap(hit)
            } else {
                h.cell.image.setImageDrawable(placeholder())
                loader.loadUri(uri, cardW, cardH) { bmp ->
                    if (bmp != null && h.cell.tag == uri) h.cell.image.setImageBitmap(bmp)
                }
            }
        }

        override fun onViewRecycled(h: CardVH) {
            h.cell.resetFx()
            h.cell.image.setImageDrawable(null)
            h.cell.tag = null
        }

        private fun placeholder() = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2E3340.toInt(), 0xFF14161D.toInt()),
        ).apply { cornerRadius = radius }
    }
}
