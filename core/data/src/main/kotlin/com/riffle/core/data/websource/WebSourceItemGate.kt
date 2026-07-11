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
 * RemoteItemFreshness + upsert-callback` and is the sole sanctioned way for ViewModels to open
 * a web-source item detail — routing through this class guarantees the freshness check,
 * network-failure stale-fallback, Room upsert, and TTL stamp all happen at one place.
 *
 * ABS and Storyteller do NOT route through this gate — they have their own sync semantics.
 *
 * Contract:
 *
 * - Within the TTL, if a persisted row exists → return [Outcome.Fresh] without hitting the
 *   network. The caller navigates directly to whatever screen reads the row.
 * - Otherwise call [Catalog.getItem]:
 *   - Success → upsert via the callback, stamp freshness (unless the fetched item is missing
 *     its cover — see below), return [Outcome.Fetched] with the fresh [CatalogItem].
 *   - `IOException` (offline, timeout, upstream error surfaced as I/O) → if a row exists
 *     (any age) return [Outcome.Stale]; if no row return [Outcome.Failed] with the cause.
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
    private val logger: Logger,
) {

    sealed interface Outcome {
        /** Freshness check passed and a Room row exists — no network call was issued. */
        data object Fresh : Outcome

        /** Network fetch succeeded; row was upserted and freshness stamped. */
        data class Fetched(val item: CatalogItem) : Outcome

        /** Fetch failed but a (possibly stale) row exists; freshness was NOT stamped. */
        data object Stale : Outcome

        /** Fetch failed and no row exists — caller may fall back to partial data. */
        data class Failed(val cause: Throwable) : Outcome
    }

    suspend fun openItem(
        sourceId: String,
        itemId: String,
        catalog: Catalog,
        upsert: suspend (CatalogItem) -> Unit,
        forceRefresh: Boolean = false,
    ): Outcome {
        val ttlMs = TTL_MS
        if (!forceRefresh &&
            freshness.withinTtl(sourceId, itemId, ttlMs) &&
            libraryObserver.getItem(sourceId, itemId) != null
        ) {
            return Outcome.Fresh
        }
        return try {
            val fetched = catalog.getItem(itemId)
            if (fetched != null) {
                upsert(fetched)
                // Missing cover = incomplete fetch. Chitanka/gramofonche items almost always
                // carry a cover; a null/blank one is usually a transient scrape miss (upstream
                // hiccup, image CDN slow, HTML variant we didn't parse). Skipping the freshness
                // stamp makes the very next open retry the fetch instead of locking in the
                // "no cover" state for 24 h. The upsert still runs so the user sees a title
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
                if (libraryObserver.getItem(sourceId, itemId) != null) Outcome.Stale
                else Outcome.Failed(NoSuchElementException("catalog.getItem returned null for $itemId"))
            }
        } catch (io: IOException) {
            logger.d(LogChannel.WebSourceCache) {
                "web-source fetch failed for $sourceId/$itemId — ${io.javaClass.simpleName}: ${io.message}"
            }
            if (libraryObserver.getItem(sourceId, itemId) != null) Outcome.Stale
            else Outcome.Failed(io)
        }
    }

    companion object {
        /** 24 hours. */
        const val TTL_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
