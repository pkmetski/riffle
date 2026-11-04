package com.riffle.feature.library

class LibraryTabVisibilityCache {
    private val cache = mutableMapOf<String, LibraryTabVisibility>()

    fun get(libraryId: String): LibraryTabVisibility? = cache[libraryId]

    fun put(libraryId: String, visibility: LibraryTabVisibility) {
        cache[libraryId] = visibility
    }
}
