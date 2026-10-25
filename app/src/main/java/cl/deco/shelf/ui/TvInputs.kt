package cl.deco.shelf.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.util.Log

/**
 * Entradas de video del televisor (HDMI, AV, sintonizador) vía TvInputManager.
 *
 * Dos trampas verificadas en la TCL de pruebas (Android 11):
 * - El intent VIEW de un canal passthrough lo reclaman también visores genéricos (un gestor de archivos): Android mostraba un
 *   selector. El intent va dirigido a la app de TV en vivo, la que declara el tipo de canal y no abre archivos cualquiera.
 * - Con la app de TV ya abierta, un intent con solo NEW_TASK trae su tarea al frente y se descarta: la TV seguía en la entrada
 *   anterior. CLEAR_TOP + SINGLE_TOP entrega el intent a la instancia viva (onNewIntent) y la entrada cambia.
 */
object TvInputs {
    /** Acción estándar de Android TV para el panel de entradas del sistema (TCL: com.tcl.suspension/.ui.main.InputActivity). */
    const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
    private const val TYPE_CHANNEL = "vnd.android.cursor.item/channel"
    private const val TYPE_CHANNELS = "vnd.android.cursor.dir/channel"

    enum class Kind { HDMI, AV, TV, OTHER }

    /**
     * [id] identifica la entrada para recordar la última usada; [last] la marca en el desplegable. [connected]: hay un cable
     * HDMI puesto en ese puerto (solo HDMI y solo si la señal es confiable en el equipo). [device] es el nombre de un equipo
     * detectado por HDMI-CEC (consola, deco).
     */
    class Source(val id: String, val label: String, val kind: Kind, val last: Boolean, val intent: Intent,
                 val device: String? = null, val connected: Boolean = false)

    private const val PREF_LAST = "last_input_id"
    private const val LIVE_ID = "livetv"

    /**
     * Cable puesto en un HDMI. Verificado en la TCL de pruebas desenchufando y enchufando una Switch: el estado pasa a
     * INPUT_STATE_CONNECTED (0) con cable y a CONNECTED_STANDBY (1) sin él (los puertos vacíos quedan en 1); DISCONNECTED (2)
     * no aparece. AV/compuesto no tiene detección (siempre 0), por eso solo se usa en HDMI.
     */
    fun hdmiPlugged(state: Int) = state == TvInputManager.INPUT_STATE_CONNECTED

    /**
     * Entradas visibles, ordenadas: HDMI, AV/componente, sintonizador y otras. Bloqueante (binder y PackageManager): llamar
     * fuera del main thread.
     */
    fun sources(ctx: Context): List<Source> {
        val out = ArrayList<Source>()
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return out
        val tvApp = liveTvActivity(ctx)
        val last = ctx.getSharedPreferences("shelf", Context.MODE_PRIVATE).getString(PREF_LAST, null)
        val inputs = try { tim.tvInputList.filter { !it.isHidden(ctx) } } catch (e: Exception) { Log.w("Shelf", "entradas: $e"); emptyList() }
        // "TV en vivo" abre la app elegida en Ajustes > Control remoto (por defecto Movistar TV); sin ella, el sintonizador
        val liveApp = cl.deco.shelf.service.KeyMap.liveTvApp(ctx)?.let { cl.deco.shelf.service.KeyMap.launchIntent(ctx, it) }
        // Equipos HDMI-CEC (Nintendo Switch, PlayStation, Xbox, decos): el framework los publica como entradas hijas del
        // puerto (getParentId), con su nombre
        val cec = inputs.filter { it.parentId != null }
        val ports = inputs.filter { it.parentId == null }
        // Estado de cable por HDMI. Si TODOS dicen "conectado", el equipo no informa el cable (valor por defecto de Android
        // para entradas sin hotplug) y no se muestra nada: mejor sin estado que con uno falso
        val hdmiStates = ports.filter { kind(it.type) == Kind.HDMI }.associate { it.id to (try { tim.getInputState(it.id) } catch (_: Exception) { -1 }) }
        val trusted = hdmiStates.size > 1 && hdmiStates.values.any { !hdmiPlugged(it) }
        for (info in ports.sortedWith(compareBy({ kind(it.type).ordinal }, { it.id }))) {
            val k = kind(info.type)
            if (k == Kind.TV && liveApp != null) { if (out.none { it.kind == Kind.TV }) out += Source(LIVE_ID, "TV en vivo", k, last == LIVE_ID, liveApp); continue }
            val dev = cec.firstOrNull { it.parentId == info.id }?.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() }
            val base = label(ctx, info, k)
            val plugged = k == Kind.HDMI && trusted && hdmiPlugged(hdmiStates[info.id] ?: -1)
            out += Source(info.id, if (dev != null) "$dev · ${base.substringAfter(" · ")}" else base, k, last == info.id,
                intentFor(info, tvApp), dev, plugged || dev != null)
        }
        if (liveApp != null && out.none { it.kind == Kind.TV }) out += Source(LIVE_ID, "TV en vivo", Kind.TV, last == LIVE_ID, liveApp) // equipos sin sintonizador
        return out
    }

    /** La entrada [inputId] si es un HDMI (para el aviso "se conectó algo"), con su nombre y equipo CEC si lo hay. */
    fun hdmiSource(ctx: Context, inputId: String): Source? =
        sources(ctx).firstOrNull { it.id == inputId && it.kind == Kind.HDMI }

    /** Panel de entradas del fabricante, si existe (respaldo cuando TvInputManager no lista nada). */
    fun systemPanel(ctx: Context): Intent? = Intent(ACTION_VIEW_INPUTS).takeIf {
        ctx.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    /** Abre la entrada. Devuelve false si ninguna Activity la acepta. */
    fun launch(ctx: Context, s: Source): Boolean = try {
        // CLEAR_TOP|SINGLE_TOP solo para canales (cambian la entrada en la app de TV ya abierta); a una app de TV en vivo
        // (Movistar) se la retoma tal como estaba, sin destruir su reproductor
        val channel = s.intent.action == Intent.ACTION_VIEW
        val flags = if (channel) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        ctx.startActivity(Intent(s.intent).addFlags(flags))
        ctx.getSharedPreferences("shelf", Context.MODE_PRIVATE).edit().putString(PREF_LAST, s.id).apply()
        true
    } catch (e: Exception) {
        Log.w("Shelf", "entrada ${s.label}: $e")
        false
    }

    private fun kind(type: Int) = when (type) {
        TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_DISPLAY_PORT, TvInputInfo.TYPE_DVI -> Kind.HDMI
        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART, TvInputInfo.TYPE_VGA -> Kind.AV
        TvInputInfo.TYPE_TUNER -> Kind.TV
        else -> Kind.OTHER
    }

    /** Nombre del sistema ("HDMI 3"); con nombre personalizado, ambos: "ATV · HDMI 3". El sintonizador es "TV en vivo". */
    private fun label(ctx: Context, info: TvInputInfo, k: Kind): String {
        if (k == Kind.TV) return "TV en vivo"
        val base = info.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: if (k == Kind.AV) "AV" else "Entrada"
        val custom = info.loadCustomLabel(ctx)?.toString()?.trim()
        return if (!custom.isNullOrEmpty() && !custom.equals(base, ignoreCase = true)) "$custom · $base" else base
    }

    /** Passthrough: URI de la entrada. Sintonizador u otras: lista de canales de esa entrada. */
    private fun intentFor(info: TvInputInfo, tvApp: ComponentName?): Intent {
        val i = if (info.isPassthroughInput) Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUriForPassthroughInput(info.id), TYPE_CHANNEL)
        else Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelsUriForInput(info.id), TYPE_CHANNELS)
        if (tvApp != null) i.component = tvApp
        return i
    }

    /**
     * App de TV en vivo: Activity que abre canales de TvContract y no es un visor genérico (que también abriría un archivo
     * cualquiera). En la TCL resuelve a com.tcl.tv/.TVActivity.
     */
    private fun liveTvActivity(ctx: Context): ComponentName? {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://android.media.tv/channel/0"), TYPE_CHANNEL)
        val generic = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://x/y"), "application/octet-stream")
        val genericPkgs = pm.queryIntentActivities(generic, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
        val ri = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName !in genericPkgs && it.activityInfo.packageName != "android" } ?: return null
        return ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
    }
}
