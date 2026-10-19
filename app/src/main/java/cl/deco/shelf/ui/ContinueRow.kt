package cl.deco.shelf.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
 * Fila "Continuar viendo" del estado GRID (estilo tvOS): Watch Next de todas las apps en tarjetas 16:9 de 320x180 px.
 * Solo imagen; título (16 sp) y subtítulo (13 sp) aparecen DEBAJO de la tarjeta enfocada. Sello de la app (28x16 dp)
 * en la esquina inferior derecha y barra de avance de 3 px con el acento si hay posición y duración.
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

    private inner class VH(
        val cell: FocusCell, val texts: LinearLayout, val title: android.widget.TextView, val sub: android.widget.TextView,
        val badge: ImageView, val track: View, val bar: View,
    ) : ViewHolder(cell)

    private inner class CardAdapter : Adapter<VH>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val c = parent.context
            val cell = FocusCell(c, cardW, cardH, padX, c.dp(10), c.dp(48), radius)
            // Sello de la app: ícono pequeño abajo a la derecha, radio 4 y sombra suave
            val badge = ImageView(c).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                elevation = c.dpf(3f)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, c.dpf(4f)) }
                }
                clipToOutline = true
            }
            cell.content.addView(badge, FrameLayout.LayoutParams(c.dp(28), c.dp(16), Gravity.BOTTOM or Gravity.END).apply { rightMargin = c.dp(6); bottomMargin = c.dp(8) })
            // Barra de avance de 3 px en el borde inferior
            val track = View(c).apply { setBackgroundColor(0x55FFFFFF); visibility = View.GONE }
            val bar = View(c)
            cell.content.addView(track, FrameLayout.LayoutParams(-1, 3, Gravity.BOTTOM))
            cell.content.addView(bar, FrameLayout.LayoutParams(0, 3, Gravity.BOTTOM or Gravity.START))
            // Texto bajo la tarjeta, solo con foco
            val t = tv(c, 16f, Ui.TEXT, "sans-serif-medium").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(8f, 0f, 1f, 0x99000000.toInt()) }
            val s = tv(c, 13f, Ui.TEXT, "sans-serif").apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = 0.7f; setShadowLayer(8f, 0f, 1f, 0x99000000.toInt()) }
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; alpha = 0f; addView(t, LinearLayout.LayoutParams(-1, -2)); addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(2) }) }
            cell.addView(texts, FrameLayout.LayoutParams(cardW + c.dp(40), -2, Gravity.TOP or Gravity.START).apply { leftMargin = padX; topMargin = c.dp(10) + cardH + c.dp(10) })
            val vh = VH(cell, texts, t, s, badge, track, bar)
            cell.onFocus = { f -> texts.animate().alpha(if (f) 1f else 0f).setDuration(Ui.FOCUS_MS).start() }
            cell.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let { onItem(it.item) } }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            h.title.text = c.item.title
            h.sub.text = c.item.subtitle.orEmpty()
            h.sub.visibility = if (c.item.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            val p = c.item.progress
            h.track.visibility = if (p != null) View.VISIBLE else View.GONE
            h.bar.setBackgroundColor(Theme.accent)
            h.bar.visibility = if (p != null) View.VISIBLE else View.GONE
            if (p != null) (h.bar.layoutParams as FrameLayout.LayoutParams).width = (cardW * p).toInt().coerceAtLeast(3)
            val uri = c.item.imageUri
            h.cell.tag = uri
            h.cell.image.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2E3340.toInt(), 0xFF14161D.toInt())).apply { cornerRadius = radius })
            if (uri != null) {
                val hit = loader.cachedUri(uri)
                if (hit != null) h.cell.image.setImageBitmap(hit)
                else loader.loadUri(uri, 320, 180) { bmp -> if (bmp != null && h.cell.tag == uri) h.cell.image.setImageBitmap(bmp) }
            }
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
            h.texts.animate().cancel(); h.texts.alpha = 0f
            h.cell.image.setImageDrawable(null)
            h.badge.setImageDrawable(null)
            h.cell.tag = null
        }
    }
}
