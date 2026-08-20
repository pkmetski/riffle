package com.riffle.app.i18n

enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    Bulgarian("bg"),
    Spanish("es-ES");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val normalized = tag.orEmpty().substringBefore(',').trim()
            if (normalized.isBlank()) return System
            return entries.firstOrNull { language ->
                if (language.tag.isBlank()) return@firstOrNull false
                val languageCode = language.tag.substringBefore('-')
                normalized == language.tag ||
                    normalized == languageCode ||
                    normalized.startsWith("$languageCode-")
            } ?: System
        }
    }
}
