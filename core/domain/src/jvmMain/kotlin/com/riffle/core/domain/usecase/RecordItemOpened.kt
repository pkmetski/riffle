package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.ReadingSessionRepository
import javax.inject.Inject

/**
 * Bump the active-Server item's local `lastOpenedAt` AND best-effort push it to ABS via the
 * reading-session sync (so other devices see the open via `mediaProgress.lastUpdate`). Offline
 * push failures are intentionally swallowed — the local stamp lifts the server timestamp via
 * `max()` on the next successful library refresh.
 */
open class RecordItemOpened @Inject constructor(
    private val libraryMutator: LibraryMutator,
    private val readingSessionRepository: ReadingSessionRepository,
) {
    open suspend operator fun invoke(itemId: String) {
        libraryMutator.markItemOpened(itemId)
        readingSessionRepository.touchOpenTimestamp(itemId)
    }
}
