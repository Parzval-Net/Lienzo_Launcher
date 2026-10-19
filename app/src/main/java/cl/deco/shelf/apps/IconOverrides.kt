package cl.deco.shelf.apps

import android.content.ComponentName
import cl.deco.shelf.R

/**
 * Tiles 5:3 artesanales, solo para apps sin banner propio o con arte pobre. No se redibujan logos de marcas
 * (esas usan siempre su banner oficial normalizado). Por paquete: si la app se desinstala, no se usa.
 */
object IconOverrides {
    const val SHELF_PKG = "cl.deco.shelf"
    const val SHELF_SETTINGS_CLASS = "shelf.settings"

    private val byPackage = mapOf(
        "com.arkiv.player.light" to R.drawable.tile_kino, // Kino TV
        "com.android.tv.settings" to R.drawable.tile_settings,
    )

    /** Logo vectorial oficial de la app y su color de marca, resueltos por nombre en runtime desde la app instalada. */
    class OfficialLogo(val drawable: String, val color: String, val fallback: Int)

    private val logos = mapOf(
        "cl.movistarplay" to OfficialLogo("logo_m", "splash_background", 0xFF019DF4.toInt()),
    )

    fun officialLogo(pkg: String): OfficialLogo? = logos[pkg]

    fun forComponent(c: ComponentName): Int? =
        if (c.packageName == SHELF_PKG && c.className == SHELF_SETTINGS_CLASS) R.drawable.tile_shelf_settings
        else byPackage[c.packageName]
}
