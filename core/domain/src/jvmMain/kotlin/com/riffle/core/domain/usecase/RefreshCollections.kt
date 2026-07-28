package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryRefreshResult
import javax.inject.Inject

/** Pull a library's collections list. Thin wrapper around [LibraryRefresher]. */
open class RefreshCollections @Inject constructor(
    private val refresher: LibraryRefresher,
) {
    open suspend operator fun invoke(libraryId: String): LibraryRefreshResult =
        refresher.refreshCollections(libraryId)
}
