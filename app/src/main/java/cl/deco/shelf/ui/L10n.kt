package cl.deco.shelf.ui

/** Helper mínimo para textos bilingües (ES/EN) reactivos al ajuste `Es.english` sin recargar recursos. */
internal fun t(es: String, en: String): String = if (Es.english) en else es
