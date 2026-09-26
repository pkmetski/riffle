package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.keepsFinishedState

/** Persist new readingProgress for the active Server's copy of an item. */
open class UpdateReadingProgress constructor(
    private val libraryMutator: LibraryMutator,
) {
    open suspend operator fun invoke(itemId: String, progress: Float) {
        // Opening a marked-as-read book and closing it on the cover must not drop it to 0%;
        // reading past the cover un-finishes it normally (see keepsFinishedState).
        val current = libraryMutator.currentReadingProgress(itemId) ?: 0f
        if (keepsFinishedState(current, progress)) return
        libraryMutator.updateReadingProgress(itemId, progress)
    }

    open suspend operator fun invoke(sourceId: String, itemId: String, progress: Float) {
        val current = libraryMutator.currentReadingProgress(sourceId, itemId) ?: 0f
        if (keepsFinishedState(current, progress)) return
        libraryMutator.updateReadingProgress(sourceId, itemId, progress)
    }
}
