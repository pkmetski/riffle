package com.riffle.core.models

enum class ServerType(val label: String) {
    AUDIOBOOKSHELF("Audiobookshelf"),
    STORYTELLER_SERVICE("Storyteller"),
    ;

    companion object {
        fun fromStorageString(value: String): ServerType =
            entries.firstOrNull { it.name == value }
                ?: if (value == LEGACY_STORYTELLER_NAME) STORYTELLER_SERVICE else AUDIOBOOKSHELF

        private const val LEGACY_STORYTELLER_NAME = "STORYTELLER"
    }
}
