package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryRefresher
import com.riffle.core.domain.LibraryRefreshResult
import javax.inject.Inject

/** Pull the active Server's library list. Thin wrapper around [LibraryRefresher]. */
open class RefreshLibraries @Inject constructor(
    private val refresher: LibraryRefresher,
) {
    open suspend operator fun invoke(): LibraryRefreshResult = refresher.refreshLibraries()
}
