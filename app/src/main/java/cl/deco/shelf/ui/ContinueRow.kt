package cl.deco.shelf.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.ImageLoader
import cl.deco.shelf.shelf.TopShelfItem
import cl.deco.shelf.shelf.TvProviderShelf

/**
 * Fila "Continuar viendo" del estado GRID: Watch Next de todas las apps en tarjetas 16:9 (320x180 px) con título,
 * subtítulo y el tile de la app en una esquina. Imágenes en diferido con downsample y la LRU del ImageLoader.
 */
class ContinueRow(
    ctx: Context,
    private val loader: ImageLoader,
    private val appFor: (String) -> AppEntry?,
    private val onItem: (TopShelfItem) -> Unit,
) : RecyclerView(ctx) {

    private val cardW = ctx.dp(160)
    private val cardH = ctx.dp(90)
    private val padX = ctx.dp(6)
    private val radius = ctx.dpf(10f)
    private val items = ArrayList<TvProviderShelf.Continue>()

    init {
        layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        itemAnimator = null
        clipToPadding = false
        clipChildren = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        setItemViewCacheSize(4)
        adapter = CardAdapter()
        visibility = INVISIBLE
    }

    fun setItems(list: List<TvProviderShelf.Continue>) {
        items.clear(); items.addAll(list)
        adapter?.notifyDataSetChanged()
        scrollToPosition(0)
    }

    private inner class VH(val cell: FocusCell, val title: android.widget.TextView, val sub: android.widget.TextView, val badge: ImageView) : ViewHolder(cell)

    private inner class CardAdapter : Adapter<VH>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val cell = FocusCell(c, cardW, cardH, padX, c.dp(10), c.dp(10), radius)
            val t = tv(c, 12f, Ui.TEXT, "sans-serif-medium").apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val s = tv(c, 10f, Ui.TEXT2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val box = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(c.dp(10), 0, c.dp(10), c.dp(8))
                addView(t, LinearLayout.LayoutParams(-1, -2))
                addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(1) })
            }
            cell.content.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val badge = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_XY }
            cell.content.addView(badge, FrameLayout.LayoutParams(c.dp(40), c.dp(22), Gravity.TOP or Gravity.START).apply { leftMargin = c.dp(6); topMargin = c.dp(6) })
            val vh = VH(cell, t, s, badge)
            cell.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { onItem(it.item) } }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            h.title.text = c.item.title
            h.sub.text = c.item.subtitle.orEmpty()
            h.sub.visibility = if (c.item.subtitle.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            val uri = c.item.imageUri
            h.cell.tag = uri
            h.cell.image.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2E3340.toInt(), 0xFF14161D.toInt())).apply { cornerRadius = radius })
            if (uri != null) {
                val hit = loader.cachedUri(uri)
                if (hit != null) h.cell.image.setImageBitmap(hit)
                else loader.loadUri(uri, 320, 180) { bmp -> if (bmp != null && h.cell.tag == uri) h.cell.image.setImageBitmap(bmp) }
            }
            // Tile de la app en pequeño en la esquina
            val app = appFor(c.pkg)
            h.badge.setImageDrawable(null)
            if (app != null) {
                val hit = loader.cached(app)
                if (hit != null) h.badge.setImageBitmap(hit)
                else loader.load(app) { bmp -> if (h.cell.tag == uri) h.badge.setImageBitmap(bmp) }
            }
        }

        override fun onViewRecycled(h: VH) {
            h.cell.resetFx()
            h.cell.image.setImageDrawable(null)
            h.badge.setImageDrawable(null)
            h.cell.tag = null
        }
    }
}
