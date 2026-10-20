package cl.deco.shelf.util

/**
 * Lista blanca de "Liberar memoria": paquetes que nunca se tocan. Lógica pura (testeable en JVM).
 * Se protege lo que sostiene el teclado, Google Play Services/Cast/control remoto, el sistema y el firmware del deco.
 */
object KillPolicy {
    private val prefixes = listOf(
        "com.google.android.gms",          // Play Services (y sus subprocesos)
        "com.google.android.gsf",
        "com.android.vending",
        "com.google.android.apps.mediashell", // Cast
        "com.google.android.tv.remote.service",
        "com.android.systemui",
        "com.sdmc.",                       // firmware del deco
        "com.droidlogic",                  // Amlogic (video, HDMI, OTA)
        "com.android.providers.",          // content providers del sistema
    )

    fun isProtected(pkg: String, imePkg: String?, ownPkg: String): Boolean = when {
        pkg == ownPkg -> true
        imePkg != null && pkg == imePkg -> true
        pkg == "android" -> true
        pkg == "tv.settings" || pkg.endsWith(".tv.settings") -> true
        pkg.contains(".providers.") || pkg.endsWith(".providers") -> true
        prefixes.any { pkg.startsWith(it) } -> true
        else -> false
    }

    /** Paquete del teclado activo a partir de "paquete/clase" (Settings.Secure.DEFAULT_INPUT_METHOD). */
    fun imePackage(defaultInputMethod: String?): String? =
        defaultInputMethod?.substringBefore('/')?.takeIf { it.isNotBlank() }
}
