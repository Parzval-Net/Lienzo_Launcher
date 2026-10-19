package cl.deco.shelf.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.ImageLoader

/**
 * Una sola lista para el dock y la grilla: [favoritas][separador][todas las apps].
 * Las favoritas aparecen también en la grilla (grilla = todas las apps).
 */
class AppAdapter(
    private val loader: ImageLoader,
    private val tileW: Int,
    private val tileH: Int,
    private val padX: Int,
    private val padY: Int,
    private val gapH: Int,
    private val radius: Float,
    private val cb: Callbacks,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    interface Callbacks {
        fun onClick(e: AppEntry)
        fun onLongClick(e: AppEntry, pos: Int)
        fun onFocus(e: AppEntry, pos: Int)
    }

    private val favs = ArrayList<AppEntry>()
    private val apps = ArrayList<AppEntry>()

    /** Tile en modo mover (clave + sección). */
    var movingKey: String? = null
    var movingFav = false

    init { setHasStableIds(true) }

    val favCount get() = favs.size
    val gridStart get() = if (favs.isEmpty()) 0 else favs.size + 1

    class VH(v: View, val cell: FocusCell?) : RecyclerView.ViewHolder(v)

    fun submit(f: List<AppEntry>, all: List<AppEntry>) {
        favs.clear(); favs.addAll(f)
        apps.clear(); apps.addAll(all)
        notifyDataSetChanged()
    }

    fun entryAt(pos: Int): AppEntry? = when {
        pos < favs.size -> favs[pos]
        favs.isEmpty() -> apps.getOrNull(pos)
        pos == favs.size -> null
        else -> apps.getOrNull(pos - favs.size - 1)
    }

    fun isFavPos(pos: Int) = pos < favs.size
    fun isGridPos(pos: Int) = pos >= gridStart
    fun isGap(pos: Int) = favs.isNotEmpty() && pos == favs.size

    fun posOf(key: String, fav: Boolean): Int {
        if (fav) return favs.indexOfFirst { it.key == key }
        val i = apps.indexOfFirst { it.key == key }
        return if (i < 0) -1 else gridStart + i
    }

    fun favKeys() = favs.map { it.key }
    fun appKeys() = apps.map { it.key }

    /** Mueve un elemento dentro de su sección. Devuelve false si el destino cae fuera. */
    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (isFavPos(from)) {
            if (to !in 0 until favs.size) return false
            favs.add(to, favs.removeAt(from))
        } else {
            val a = from - gridStart
            val b = to - gridStart
            if (a !in 0 until apps.size || b !in 0 until apps.size) return false
            apps.add(b, apps.removeAt(a))
        }
        notifyItemMoved(from, to)
        return true
    }

    override fun getItemCount() = favs.size + (if (favs.isEmpty()) 0 else 1) + apps.size

    override fun getItemViewType(position: Int) = if (isGap(position)) 1 else 0

    override fun getItemId(position: Int): Long {
        val e = entryAt(position) ?: return -1L
        return e.key.hashCode().toLong() * 2 + (if (isFavPos(position)) 1 else 0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        if (viewType == 1) {
            val v = View(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gapH)
            return VH(v, null)
        }
        val cell = FocusCell(parent.context, tileW, tileH, padX, padY, radius)
        val vh = VH(cell, cell)
        cell.setOnClickListener { entryAt(vh.bindingAdapterPosition)?.let(cb::onClick) }
        cell.setOnLongClickListener {
            val p = vh.bindingAdapterPosition
            entryAt(p)?.let { e -> cb.onLongClick(e, p); true } ?: false
        }
        cell.onFocus = { f ->
            if (f) {
                val p = vh.bindingAdapterPosition
                entryAt(p)?.let { e -> cb.onFocus(e, p) }
            }
        }
        return vh
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val cell = h.cell ?: return
        val e = entryAt(position) ?: return
        cell.tag = e.key
        cell.setMoving(movingKey == e.key && movingFav == isFavPos(position))
        val hit = loader.cached(e)
        if (hit != null) {
            cell.image.setImageBitmap(hit)
        } else {
            cell.image.setImageDrawable(null)
            loader.load(e) { bmp -> if (cell.tag == e.key) cell.image.setImageBitmap(bmp) }
        }
    }

    override fun onViewRecycled(h: VH) {
        h.cell?.let {
            it.resetFx()
            it.image.setImageDrawable(null)
            it.tag = null
        }
    }
}
