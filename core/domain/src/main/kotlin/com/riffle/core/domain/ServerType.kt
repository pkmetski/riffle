package com.riffle.core.domain

enum class ServerType {
    AUDIOBOOKSHELF,
    STORYTELLER,
    ;

    companion object {
        fun fromStorageString(value: String): ServerType =
            entries.firstOrNull { it.name == value } ?: AUDIOBOOKSHELF
    }
}
