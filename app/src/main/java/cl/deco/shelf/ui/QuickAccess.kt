package cl.deco.shelf.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import cl.deco.shelf.R

/**
 * Accesos rápidos a ajustes del sistema. Cada acceso tiene intents candidatos en orden; se usa el primero que resuelve
 * a una Activity concreta (no al selector). Verificado en el deco: Bluetooth, Imagen y Sonido no tienen action estándar
 * en com.android.tv.settings, por eso llevan componente explícito.
 */
object QuickAccess {
    class Item(val label: String, val icon: Int, val candidates: List<Intent>)

    private val tvs = "com.android.tv.settings"
    private fun comp(cls: String) = Intent().setComponent(ComponentName(tvs, "$tvs$cls"))

    val items = listOf(
        Item("Wi-Fi", R.drawable.ic_q_wifi, listOf(Intent(Settings.ACTION_WIFI_SETTINGS))),
        Item("Bluetooth", R.drawable.ic_q_bluetooth, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), comp(".accessories.AddAccessoryActivity"))),
        Item("Imagen", R.drawable.ic_q_display, listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS), comp(".device.displaysound.DisplaySoundActivity"))),
        Item("Sonido", R.drawable.ic_q_sound, listOf(Intent("android.settings.SOUND_SETTINGS"), comp(".device.displaysound.DisplaySoundActivity"))),
        Item("Almacenamiento", R.drawable.ic_q_storage, listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))),
        Item("Ajustes de Android", R.drawable.ic_q_android, listOf(Intent(Settings.ACTION_SETTINGS))),
    )

    val storage get() = items[4]

    fun launch(ctx: Context, item: Item) {
        val pm = ctx.packageManager
        val i = (item.candidates + Intent(Settings.ACTION_SETTINGS)).firstOrNull {
            val ri = pm.resolveActivity(it, 0)
            ri != null && ri.activityInfo.packageName != "android"
        }
        try { ctx.startActivity((i ?: Intent(Settings.ACTION_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(ctx, "No se pudo abrir ${item.label}", Toast.LENGTH_SHORT).show() }
    }
}
