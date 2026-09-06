package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator

/** Persist new readingProgress for the active Server's copy of an item. */
open class UpdateReadingProgress constructor(
    private val libraryMutator: LibraryMutator,
) {
    open suspend operator fun invoke(itemId: String, progress: Float) {
        libraryMutator.updateReadingProgress(itemId, progress)
    }
}
