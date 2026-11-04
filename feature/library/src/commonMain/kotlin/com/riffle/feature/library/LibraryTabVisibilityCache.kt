package com.riffle.feature.library

class LibraryTabVisibilityCache {
    // @Volatile reference to an immutable snapshot: reads (IO thread from combine) always see the
    // latest reference without touching mutable HashMap internals; writes (Main thread only, from
    // viewModelScope collector) replace the reference atomically. Single-writer → no lost updates.
    @Volatile
    private var cache: Map<String, LibraryTabVisibility> = emptyMap()

    fun get(libraryId: String): LibraryTabVisibility? = cache[libraryId]

    fun put(libraryId: String, visibility: LibraryTabVisibility) {
        cache = cache + (libraryId to visibility)
    }
}
