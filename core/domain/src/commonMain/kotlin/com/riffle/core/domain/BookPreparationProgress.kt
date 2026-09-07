package com.riffle.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, optional progress signal for Sources that must build a book before it can be opened
 * (e.g. O'Reilly, which scrapes and synthesizes an EPUB on open). The reader's loading state shows a
 * determinate "Preparing book… N of M" when [state] is non-null, so a multi-minute preparation reads
 * as progress rather than a frozen spinner.
 *
 * Generic by design: any Source may report into it; Sources that open instantly never do, so their
 * loading UI is unchanged (state stays null → plain spinner).
 */
class BookPreparationProgress {
    data class Progress(val done: Int, val total: Int)

    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state.asStateFlow()

    /** Report progress preparing the current book. [total] is the number of units (e.g. chapters). */
    fun report(done: Int, total: Int) {
        _state.value = Progress(done.coerceIn(0, total), total)
    }

    /** Clear when preparation finishes or fails (returns the loading UI to a plain spinner). */
    fun clear() {
        _state.value = null
    }
}
