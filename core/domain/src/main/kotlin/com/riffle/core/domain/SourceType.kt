package com.riffle.core.domain

enum class SourceType(
    /**
     * True when this Source's catalogue is too large to Room-mirror — the library screen must
     * browse it remotely on demand rather than reading from `library_items`. `library_items` for
     * these Sources only holds the handful of rows the user has already opened (ADR 0042). UI
     * routing keys off this flag instead of enumerating the type set so a future unbounded Source
     * (OPDS, Gutenberg, …) inherits the remote-browse UX without touching MainScreen.
     */
    val isUnboundedCatalog: Boolean = false,
) {
    ABS,
    LOCAL_FILES,
    CHITANKA(isUnboundedCatalog = true),
}
