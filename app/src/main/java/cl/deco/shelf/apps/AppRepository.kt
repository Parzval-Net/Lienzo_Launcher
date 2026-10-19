package cl.deco.shelf.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/** Lista de apps con LEANBACK_LAUNCHER. Llamar fuera del main thread. */
class AppRepository(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    private val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    fun load(): List<AppEntry> =
        pm.queryIntentActivities(query, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), componentOf(it)) }
            .sortedBy { it.label.lowercase() }
            .toList()

    fun launchIntent(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun componentOf(ri: ResolveInfo) =
        android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
}
