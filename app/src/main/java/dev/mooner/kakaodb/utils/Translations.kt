package dev.mooner.kakaodb.utils

import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate

fun tr(english: String, korean: String): String =
    translate {
        Locale.ENGLISH { english }
        Locale.KOREAN  { korean }
    }
