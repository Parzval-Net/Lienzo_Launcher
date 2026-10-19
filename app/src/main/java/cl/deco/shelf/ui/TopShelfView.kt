package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.ImageLoader
import cl.deco.shelf.data.art.Artwork
import cl.deco.shelf.shelf.TopShelfItem

/**
 * Zona superior: carrusel de tarjetas 16:9 si la app enfocada publica contenido; si no, pie de obra
 * (estilo Frame) sobre el arte de fondo. Crossfade de 200 ms. Se colapsa al bajar a la grilla.
 */
class TopShelfView(
    ctx: Context,
    private val loader: ImageLoader,
    private val onItem: (TopShelfItem) -> Unit,
) : FrameLayout(ctx) {

    private val cardW = ctx.dp(256)
    private val cardH = ctx.dp(144)
    private val padX = ctx.dp(12)
    private val padY = ctx.dp(12)
    private val radius = ctx.dpf(12f)

    private val items = ArrayList<TopShelfItem>()

    private val carousel = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        setHasFixedSize(true)
        itemAnimator = null
        clipToPadding = false
        clipChildren = false
        setPadding(ctx.dp(48) - padX, 0, ctx.dp(48), 0)
        visibility = INVISIBLE
        alpha = 0f
        isFocusable = false
    }

    private val capTitle = tv(ctx, 20f, Ui.TEXT, "sans-serif-medium")
    private val capArtist = tv(ctx, 14f, Ui.TEXT)
    private val capMuseum = tv(ctx, 12f, Ui.TEXT2)
    private val caption = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(48), 0, 0, ctx.dp(12))
        for (t in listOf(capTitle, capArtist, capMuseum)) {
            t.setShadowLayer(ctx.dpf(4f), 0f, ctx.dpf(1f), 0xCC000000.toInt())
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(t, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(2) })
        }
        visibility = INVISIBLE
        alpha = 0f
    }
    private var hasCaption = false
    private var showingItems = false
    private var collapsed = false

    init {
        clipChildren = false
        carousel.adapter = CardAdapter()
        addView(carousel, LayoutParams(-1, ctx.dp(168), Gravity.TOP))
        addView(caption, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
    }

    fun setArt(a: Artwork?) {
        hasCaption = a != null
        if (a != null) {
            capTitle.text = a.title
            capArtist.text = listOfNotNull(a.artist, a.year).joinToString(", ")
            capMuseum.text = a.museum
        }
        fade(caption, hasCaption && !showingItems)
    }

    fun show(list: List<TopShelfItem>) {
        val on = list.isNotEmpty()
        if (on) {
            items.clear(); items.addAll(list)
            carousel.adapter?.notifyDataSetChanged()
            carousel.scrollToPosition(0)
        }
        showingItems = on
        fade(carousel, on)
        fade(caption, !on && hasCaption)
    }

    /** Colapsa (desvanece y sube) al enfocar la grilla; devuelve al estado normal al volver al dock. */
    fun collapse(on: Boolean) {
        if (on == collapsed) return
        collapsed = on
        animate().cancel()
        if (on) {
            animate().alpha(0f).translationY(-context.dpf(16f)).setDuration(200).setInterpolator(Ui.DECEL)
                .withEndAction { visibility = INVISIBLE }.start()
        } else {
            visibility = VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(Ui.DECEL).start()
        }
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
            val cell = FocusCell(ctx, cardW, cardH, padX, padY, radius)
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
