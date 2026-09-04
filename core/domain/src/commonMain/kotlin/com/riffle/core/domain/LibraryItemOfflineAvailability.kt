package com.riffle.core.domain

import com.riffle.core.models.LibraryItem

interface LibraryItemOfflineAvailability {
    fun isAvailableOffline(item: LibraryItem): Boolean
}
