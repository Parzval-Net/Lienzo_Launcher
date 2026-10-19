package cl.deco.shelf.apps

import cl.deco.shelf.R

/** Paquete a drawable 5:3 propio. Tiene prioridad sobre el banner/ícono de la app. */
object IconOverrides {
    private val map = mapOf(
        "com.arkiv.player.light" to R.drawable.tile_kino, // Kino TV
    )

    fun forPackage(pkg: String): Int? = map[pkg]
}
