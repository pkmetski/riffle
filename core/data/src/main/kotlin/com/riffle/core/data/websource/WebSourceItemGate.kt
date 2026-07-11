package com.riffle.core.data.websource

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import java.io.IOException
import javax.inject.Inject

/**
 * Time-limited item cache for web sources (ADR 0043). Composes `Catalog + LibraryObserver +
 * RemoteItemFreshness + WebSourceLibraryItemUpserter` and is the sole sanctioned way for
 * ViewModels to open a web-source item detail — routing through this class guarantees the
 * freshness check, network-failure stale-fallback, Room upsert, and TTL stamp all happen at
 * one place. Every current (Chitanka, Gutenberg) and future web source that ships a browse
 * ViewModel MUST route `openDetail` through this gate; there is no other sanctioned path.
 *
 * ABS and Storyteller do NOT route through this gate — they have their own sync semantics.
 *
 * Contract:
 *
 * - Within the TTL, if a persisted row exists → return [Outcome.Fresh] without hitting the
 *   network. The caller navigates directly to whatever screen reads the row.
 * - Otherwise call [Catalog.getItem]:
 *   - Success → upsert the fetched item via [WebSourceLibraryItemUpserter], stamp freshness
 *     (unless the fetched item is missing its cover — see below), return [Outcome.Fetched].
 *   - `IOException` (offline, timeout, upstream error surfaced as I/O) or a null result:
 *     - Row exists (any age) → return [Outcome.Stale] (no upsert, no stamp).
 *     - No row → upsert the [listing] item as a last-resort fallback so navigation still
 *       lands on a detail screen with the fields the search-results HTML exposed
 *       (description/series/year/etc. may be null). Freshness is NOT stamped so the next
 *       open retries the fetch. Return [Outcome.Failed] with the cause for logging.
 *
 * The fallback branch is what lets every new web source ship without hand-rolling the
 * "offline / detail-fetch 429 / catalog returned null" navigation-doesn't-break plumbing.
 *
 * Cover-missing exception: if [CatalogItem.coverUrl] is null/blank on the fetched item, we
 * upsert the row but skip [RemoteItemFreshness.stamp]. That treats the fetch as incomplete
 * so the very next open retries instead of locking in the "no cover" state for the full TTL.
 *
 * Pull-to-refresh passes `forceRefresh = true`, which bypasses the TTL check but still uses
 * the stale-fallback branch if the network fetch fails.
 */
class WebSourceItemGate @Inject constructor(
    private val libraryObserver: LibraryObserver,
    private val freshness: RemoteItemFreshness,
    private val upserter: WebSourceLibraryItemUpserter,
    private val logger: Logger,
) {

    sealed interface Outcome {
        /** Freshness check passed and a Room row exists — no network call was issued. */
        data object Fresh : Outcome

        /** Network fetch succeeded; row was upserted and freshness stamped. */
        data class Fetched(val item: CatalogItem) : Outcome

        /** Fetch failed but a (possibly stale) row exists; freshness was NOT stamped. */
        data object Stale : Outcome

        /**
         * Fetch failed and no row existed. The gate has already upserted [listing] so the
         * caller can safely navigate to the detail screen; [cause] is exposed for logging.
         */
        data class Failed(val cause: Throwable) : Outcome
    }

    suspend fun openItem(
        sourceId: String,
        listing: CatalogItem,
        catalog: Catalog,
        forceRefresh: Boolean = false,
    ): Outcome {
        val itemId = listing.id
        if (!forceRefresh &&
            freshness.withinTtl(sourceId, itemId, TTL_MS) &&
            libraryObserver.getItem(sourceId, itemId) != null
        ) {
            return Outcome.Fresh
        }
        return try {
            val fetched = catalog.getItem(itemId)
            if (fetched != null) {
                upserter.upsert(sourceId, fetched)
                // Missing cover = incomplete fetch. Chitanka/gramofonche/Gutendex items almost
                // always carry a cover; a null/blank one is usually a transient scrape miss
                // (upstream hiccup, image CDN slow, HTML variant we didn't parse). Skipping the
                // freshness stamp makes the very next open retry the fetch instead of locking in
                // the "no cover" state for 24 h. The upsert still runs so the user sees a title
                // in the browse-detail transition — only the freshness bookkeeping is deferred.
                if (fetched.coverUrl.isNullOrBlank()) {
                    logger.d(LogChannel.WebSourceCache) {
                        "web-source fetch for $sourceId/$itemId has no cover — skipping freshness stamp so retrieval retries"
                    }
                } else {
                    freshness.stamp(sourceId, itemId)
                }
                Outcome.Fetched(fetched)
            } else {
                fallbackForFailure(
                    sourceId = sourceId,
                    listing = listing,
                    cause = NoSuchElementException("catalog.getItem returned null for $itemId"),
                )
            }
        } catch (io: IOException) {
            logger.d(LogChannel.WebSourceCache) {
                "web-source fetch failed for $sourceId/$itemId — ${io.javaClass.simpleName}: ${io.message}"
            }
            fallbackForFailure(sourceId = sourceId, listing = listing, cause = io)
        }
    }

    private suspend fun fallbackForFailure(
        sourceId: String,
        listing: CatalogItem,
        cause: Throwable,
    ): Outcome {
        val itemId = listing.id
        return if (libraryObserver.getItem(sourceId, itemId) != null) {
            Outcome.Stale
        } else {
            // Last-resort upsert so tap → detail still works. No stamp — next open retries.
            upserter.upsert(sourceId, listing)
            Outcome.Failed(cause)
        }
    }

    companion object {
        /** 24 hours. */
        const val TTL_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
