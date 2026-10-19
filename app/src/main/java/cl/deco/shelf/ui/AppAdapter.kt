package cl.deco.shelf.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.ImageLoader

class AppAdapter(
    private val loader: ImageLoader,
    private val cellW: Int,
    private val cellH: Int,
    private val onClick: (AppEntry) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppEntry> = emptyList()

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val root: FrameLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root) {
        var key: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        val pad = (6 * d).toInt()
        val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(cellW, cellH)
        }
        val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            setPadding(pad, 0, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = android.view.Gravity.BOTTOM }
            visibility = View.GONE
        }
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(cellW + 2 * pad, cellH + 2 * pad)
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isClickable = true
            background = focusBackground(d)
            addView(image)
            addView(label)
        }
        val vh = VH(root, image, label)
        root.setOnClickListener { items.getOrNull(vh.bindingAdapterPosition)?.let(onClick) }
        root.setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(100).start()
            label.visibility = if (focused) View.VISIBLE else View.GONE
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.key = e.key
        h.label.text = e.label
        val hit = loader.cached(e)
        if (hit != null) {
            h.image.setImageBitmap(hit)
        } else {
            h.image.setImageDrawable(null)
            loader.load(e) { bmp -> if (h.key == e.key) h.image.setImageBitmap(bmp) }
        }
    }

    override fun onViewRecycled(h: VH) {
        h.image.setImageDrawable(null)
        h.key = null
    }

    override fun getItemCount() = items.size

    private fun focusBackground(d: Float): StateListDrawable {
        fun box(color: Int, stroke: Int) = GradientDrawable().apply {
            setColor(color)
            setStroke((3 * d).toInt(), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(0xFF202020.toInt(), Color.WHITE))
            addState(intArrayOf(), box(0xFF101010.toInt(), Color.TRANSPARENT))
        }
    }
}
