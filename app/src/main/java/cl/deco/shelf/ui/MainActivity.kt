package cl.deco.shelf.ui

import android.app.Activity
import android.content.ComponentCallbacks2
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.deco.shelf.apps.AppRepository
import cl.deco.shelf.apps.ImageLoader
import java.util.concurrent.Executors

/** Placeholder: la UI final la definen otros. Activity plana, sin appcompat. */
class MainActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var loader: ImageLoader
    private lateinit var adapter: AppAdapter
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val cellW = (200 * d).toInt()
        val cellH = (112 * d).toInt()

        repo = AppRepository(this)
        loader = ImageLoader(this, cellW, cellH)
        adapter = AppAdapter(loader, cellW, cellH) { startActivity(repo.launchIntent(it)) }

        val list = RecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            layoutManager = GridLayoutManager(this@MainActivity, 2, GridLayoutManager.HORIZONTAL, false)
            this.adapter = this@MainActivity.adapter
            setHasFixedSize(true)
            clipToPadding = false
            val p = (48 * d).toInt()
            setPadding(p, p, p, p)
            itemAnimator = null
            isFocusable = false
        }
        setContentView(list)
    }

    override fun onResume() {
        super.onResume()
        io.execute {
            val apps = repo.load()
            runOnUiThread { if (!isDestroyed) adapter.submit(apps) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) loader.trim()
    }

    override fun onDestroy() {
        io.shutdownNow()
        loader.shutdown()
        super.onDestroy()
    }

    @Deprecated("Home ya es la raíz")
    override fun onBackPressed() { /* launcher: ignorar back */ }
}
