package cl.deco.shelf.guard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Modo seguro (proceso ":guard"): lista vertical de apps con ícono y nombre. Sin arte, sin red, sin TvProvider, sin Graph.
 * Se limpia con el botón o tras 10 minutos sin crashes.
 */
class SafeActivity : Activity() {

    private class Item(val label: CharSequence, val intent: Intent, val info: ResolveInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101218.toInt())
            setPadding(dp(48), dp(28), dp(48), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Lienzo se recuperó de un error"
            setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = "Modo seguro: sin fondos de arte, datos de red ni contenido de las apps. Se desactiva solo tras 10 minutos sin errores."
            setTextColor(0xFFB8B8C0.toInt()); textSize = 15f
            setPadding(0, dp(4), 0, dp(12))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = button("Volver al modo normal") { leaveSafeMode() }
        row.addView(back, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(12) })
        row.addView(button("Abrir Ajustes de Android") {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(row)

        val items = loadApps()
        val list = ListView(this).apply {
            divider = null
            isFocusable = true
            setSelector(GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(8).toFloat() })
            adapter = object : BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(i: Int) = items[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, cv: View?, p: ViewGroup): View {
                    val it = items[i]
                    val r = LinearLayout(this@SafeActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    }
                    r.addView(ImageView(this@SafeActivity).apply { setImageDrawable(it.info.loadIcon(packageManager)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                    r.addView(TextView(this@SafeActivity).apply {
                        text = it.label; setTextColor(Color.WHITE); textSize = 19f; setPadding(dp(16), 0, 0, 0)
                    })
                    r.contentDescription = it.label
                    return r
                }
            }
            setOnItemClickListener { _, _, pos, _ ->
                try { startActivity(items[pos].intent) } catch (_: Exception) { }
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        setContentView(root)
        back.requestFocus()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(0xFF2B3040.toInt()); cornerRadius = 12f * resources.displayMetrics.density }
        stateListAnimator = null
        setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setColor(if (f) 0xFF4A5470.toInt() else 0xFF2B3040.toInt()) }
        setOnClickListener { onClick() }
    }

    private fun loadApps(): List<Item> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<Item>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                out += Item(ri.loadLabel(pm), i, ri)
            }
        }
        return out.sortedBy { it.label.toString().lowercase() }
    }

    override fun onResume() {
        super.onResume()
        // Tras 10 minutos sin crashes el modo seguro se limpia y se vuelve al home normal
        if (!CrashLog.safeMode(this)) leaveSafeMode()
    }

    private fun leaveSafeMode() {
        CrashLog.clear(this)
        startActivity(Intent(this, Class.forName("cl.deco.shelf.ui.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* es un home: ignorar back */ }

    companion object {
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, SafeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
