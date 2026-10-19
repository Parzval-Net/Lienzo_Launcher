package cl.deco.shelf.apps

import android.content.ComponentName

data class AppEntry(
    val label: String,
    val component: ComponentName,
) {
    val key: String get() = component.flattenToShortString()
}
