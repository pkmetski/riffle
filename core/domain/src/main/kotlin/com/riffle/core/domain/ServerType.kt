package com.riffle.core.domain

enum class ServerType(val label: String) {
    AUDIOBOOKSHELF("Audiobookshelf"),
    STORYTELLER("Storyteller"),
    ;

    companion object {
        fun fromStorageString(value: String): ServerType =
            entries.firstOrNull { it.name == value } ?: AUDIOBOOKSHELF
    }
}
