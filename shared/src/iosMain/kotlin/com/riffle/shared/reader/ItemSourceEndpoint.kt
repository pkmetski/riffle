package com.riffle.shared.reader

import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source

/**
 * Where an item's bytes live, and the credential to ask with.
 *
 * Resolved from the item's **own** `sourceId`, never from whichever source happens to be active.
 * The three iOS downloaders each did `sourceRepository.getActive()` while holding a
 * [LibraryItem] that carries its source — so opening a book belonging to a non-active source
 * fetched from the wrong host with the wrong token, and "fixed" itself only because most users
 * have one source. Android hit the same bug on the position path (`activeSource.id` vs
 * `item.sourceId`) and fixed it the same way.
 *
 * Also exists so the resolve-source-then-token dance has one definition instead of three.
 */
data class ItemSourceEndpoint(val source: Source, val token: String) {
    /** ABS file endpoint for [item]'s [fileIno], with no trailing slash on the base URL. */
    fun absFileUrl(item: LibraryItem, fileIno: String): String =
        "${source.url.value.trimEnd('/')}/api/items/${item.id}/file/$fileIno"
}

/**
 * Resolve [item]'s own source and token, or null when either is unavailable.
 *
 * Deliberately does **not** fall back to the active source: that fallback is the bug, because it
 * silently produces a request against a host that does not hold the item.
 */
suspend fun resolveItemEndpoint(
    sourceRepository: SourceRepository,
    tokenStorage: TokenStorage,
    item: LibraryItem,
): ItemSourceEndpoint? {
    val source = sourceRepository.getById(item.sourceId) ?: return null
    val token = tokenStorage.getToken(source.id) ?: return null
    return ItemSourceEndpoint(source, token)
}
