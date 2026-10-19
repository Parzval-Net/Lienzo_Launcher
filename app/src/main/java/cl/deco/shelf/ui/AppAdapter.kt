package cl.deco.shelf.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.AppEntry
import cl.deco.shelf.apps.ImageLoader

/** Geometría de un tipo de celda (dock o grilla), en px. */
class CellGeo(val tileW: Int, val tileH: Int, val padX: Int, val padTop: Int, val padBottom: Int, val label: Boolean)

/**
 * Datos del dock (favoritas, una fila) y de la grilla (resto de apps), con dos adapters.
 * Cada app se identifica con un "slot": índice de favorita (0..) o GRID + índice en la grilla.
 */
class AppAdapter(
    private val loader: ImageLoader,
    private val dockGeo: CellGeo,
    private val gridGeo: CellGeo,
    private val radius: Float,
    private val cb: Callbacks,
) {

    interface Callbacks {
        fun onClick(e: AppEntry)
        fun onLongClick(e: AppEntry, slot: Int)
        fun onFocus(e: AppEntry, slot: Int)
    }

    companion object {
        const val GRID = 10000
    }

    private val favs = ArrayList<AppEntry>()
    private val apps = ArrayList<AppEntry>()
    val dock = TileAdapter(true)
    val grid = TileAdapter(false)

    /** Claves de apps instaladas recientemente (etiqueta "Nuevo" hasta abrirlas). */
    var newKeys: Set<String> = emptySet()

    /** Tile en modo mover (clave + sección). */
    var movingKey: String? = null
    var movingFav = false

    val favCount get() = favs.size
    val gridCount get() = apps.size

    fun isFav(slot: Int) = slot < GRID
    fun hasItems() = favs.isNotEmpty() || apps.isNotEmpty()

    /** Devuelve false (sin tocar nada) si la lista no cambió, para no perder el foco. */
    fun submit(f: List<AppEntry>, all: List<AppEntry>): Boolean {
        if (f == favs && all == apps) return false
        favs.clear(); favs.addAll(f)
        apps.clear(); apps.addAll(all)
        dock.notifyDataSetChanged()
        grid.notifyDataSetChanged()
        return true
    }

    fun entryAt(slot: Int): AppEntry? =
        if (slot < GRID) favs.getOrNull(slot) else apps.getOrNull(slot - GRID)

    /** Slot de la app con esa clave en la sección pedida, o -1. */
    fun slotOf(key: String, fav: Boolean): Int {
        if (fav) return favs.indexOfFirst { it.key == key }
        val i = apps.indexOfFirst { it.key == key }
        return if (i < 0) -1 else GRID + i
    }

    fun favKeys() = favs.map { it.key }
    fun appKeys() = apps.map { it.key }

    /** Mueve dentro de la sección. Devuelve false si el destino cae fuera. */
    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from < GRID) {
            if (to !in 0 until favs.size) return false
            favs.add(to, favs.removeAt(from))
            dock.notifyItemMoved(from, to)
        } else {
            val a = from - GRID
            val b = to - GRID
            if (a !in 0 until apps.size || b !in 0 until apps.size) return false
            apps.add(b, apps.removeAt(a))
            grid.notifyItemMoved(a, b)
        }
        return true
    }

    /** Vuelve a enlazar todos los tiles (cambió el estilo de íconos). */
    fun refreshAll() { dock.notifyDataSetChanged(); grid.notifyDataSetChanged() }

    /** Vuelve a enlazar los tiles de un paquete (su ícono cambió por una actualización). */
    fun refreshPkg(pkg: String) {
        favs.forEachIndexed { i, e -> if (e.component.packageName == pkg) dock.notifyItemChanged(i) }
        apps.forEachIndexed { i, e -> if (e.component.packageName == pkg) grid.notifyItemChanged(i) }
    }

    fun refreshSlot(slot: Int) {
        if (slot < 0) return
        if (slot < GRID) dock.notifyItemChanged(slot) else grid.notifyItemChanged(slot - GRID)
    }

    class TileVH(val cell: FocusCell) : RecyclerView.ViewHolder(cell)

    inner class TileAdapter(private val isDock: Boolean) : RecyclerView.Adapter<TileVH>() {
        private val geo get() = if (isDock) dockGeo else gridGeo
        private val list get() = if (isDock) favs else apps
        private fun slotFor(pos: Int) = if (isDock) pos else GRID + pos

        init { setHasStableIds(true) }

        override fun getItemCount() = list.size
        override fun getItemId(position: Int) = list[position].key.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileVH {
            val g = geo
            val cell = FocusCell(parent.context, g.tileW, g.tileH, g.padX, g.padTop, g.padBottom, radius, withLabel = g.label)
            val vh = TileVH(cell)
            cell.setOnClickListener { entryAt(slotFor(vh.bindingAdapterPosition))?.let(cb::onClick) }
            cell.setOnLongClickListener {
                val s = slotFor(vh.bindingAdapterPosition)
                entryAt(s)?.let { e -> cb.onLongClick(e, s); true } ?: false
            }
            cell.onFocus = { f ->
                if (f) {
                    val s = slotFor(vh.bindingAdapterPosition)
                    entryAt(s)?.let { e -> cb.onFocus(e, s) }
                }
            }
            return vh
        }

        override fun onBindViewHolder(h: TileVH, position: Int) {
            val e = list[position]
            val cell = h.cell
            cell.tag = e.key
            cell.label?.text = e.label
            cell.setMoving(movingKey == e.key && movingFav == isDock)
            cell.setNew(!isDock && e.key in newKeys)
            val hit = loader.cached(e)
            if (hit != null) {
                cell.image.setImageBitmap(hit)
            } else {
                cell.image.setImageDrawable(null)
                loader.load(e) { bmp -> if (cell.tag == e.key) cell.image.setImageBitmap(bmp) }
            }
        }

        override fun onViewRecycled(h: TileVH) {
            h.cell.resetFx()
            h.cell.image.setImageDrawable(null)
            h.cell.tag = null
        }
    }
}
