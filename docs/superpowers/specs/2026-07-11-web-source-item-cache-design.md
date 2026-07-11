# Web-source item caching — design

**Date:** 2026-07-11
**Scope:** chitanka, gramofonche, and future web sources (Gutenberg, etc.). Not ABS or Storyteller — they have their own sync semantics.

## Problem

Every browse, tap-back-tap, author page visit, and detail open currently hits chitanka.info / gramofonche.com on the network. This wastes remote server capacity and risks rate-limiting (429). Repeated fetches of the same page within a short window are the common case: users flip between list and detail, revisit the same author, and reopen books they've already added.

We need a generic cache for all current and future web sources that:

- reduces remote load,
- keeps items accessible even when the cache is expired and the device is offline,
- expires entries after a limited time so users eventually see fresh data,
- lets the user force-refresh when they suspect staleness.

## Design

Two layers, both governed by a single 24-hour TTL.

### Layer 1 — HTTP cache (raw HTML on disk)

- Wire OkHttp's built-in `Cache` (10 MB, disk-backed) onto the shared `OkHttpClient` used by web sources.
- Add a `NetworkInterceptor` that rewrites upstream responses to `Cache-Control: public, max-age=86400`, since chitanka and gramofonche send no cache headers.
- Add an offline-fallback interceptor that, on `IOException` during a network attempt, retries the same request with `Cache-Control: only-if-cached, max-stale=Int.MAX_VALUE` so OkHttp serves any previously-cached copy regardless of age. If nothing is cached, the error propagates normally.

Any current or future web source that shares this `OkHttpClient` inherits caching for free.

**Serves:** listing pages (category, author index, series index), connectivity pings, within-session dedup of any URL. **Does not gate detail-open** — that's Layer 2's job.

### Layer 2 — Room freshness gate on parsed detail

Detail results are already persisted (chitanka writes into the shared library-item table via `ChitankaLibraryItemUpserter`). The parsed Room row is the persistent representation; a new sibling table tracks when it was last refreshed.

New table:

```
remote_item_freshness (
  source_id      TEXT NOT NULL,
  remote_id      TEXT NOT NULL,
  last_fetched_at INTEGER NOT NULL,
  PRIMARY KEY (source_id, remote_id)
)
```

The table lives alongside the existing item tables but is scoped to web sources — the shared library-item table stays untouched (per decision: ABS shouldn't grow a `last_fetched_at` column it doesn't use).

Access helper (new class in `core/data`, injectable):

```
class RemoteItemFreshness {
  suspend fun withinTtl(sourceId: String, remoteId: String, ttlMs: Long): Boolean
  suspend fun stamp(sourceId: String, remoteId: String)
  suspend fun clear(sourceId: String, remoteId: String)   // pull-to-refresh
}
```

### Detail-open flow

The gate lives at the **repository layer**, not inside `Catalog`. Reason: `Catalog` implementations sit in `core:catalog-*` modules that intentionally don't depend on `core:data` — they can't read the persisted library-item row. Keeping `Catalog.getItem` pure (always fetches fresh, matching `AbsCatalog`) and putting the freshness policy above it is the correct layering.

New service in `core:data`, `WebSourceItemGate`:

```
class WebSourceItemGate @Inject constructor(
    private val libraryObserver: LibraryObserver,
    private val freshness: RemoteItemFreshness,
    private val upserter: WebSourceLibraryItemUpserter,  // small facade over existing upserters
) {
    suspend fun openItem(
        source: Source,
        catalog: Catalog,
        itemId: String,
        forceRefresh: Boolean = false,
    ): CatalogItem?
}
```

Flow inside `openItem`:

1. If `!forceRefresh` and `freshness.withinTtl(source.id, itemId, TTL_24H)` and a Room row exists → return the row. No network.
2. Otherwise call `catalog.getItem(itemId)`:
   - **Success** → `upserter.upsert(source, item)`, `freshness.stamp(source.id, itemId)`, return `item`.
   - **Failure (`IOException`, `ChitankaHttpException`, 429-exhausted, etc.)** → if a Room row exists (any age) → return it as a stale-fallback. If no row → propagate the error.

Pull-to-refresh calls `openItem(..., forceRefresh = true)`.

Net effect: any item the user has previously opened remains accessible offline indefinitely. The TTL governs when we *prefer* fresh over cached — it never gates *access*.

### Refresh semantics

- **Pull-to-refresh on a detail screen** → `openItem(..., forceRefresh = true)`.
- **Pull-to-refresh on a list screen** → OkHttp request adds `Cache-Control: no-cache` so listing pages bypass Layer 1.
- **No background refresh.** Items in the user's library refresh only when opened.
- **No per-URL TTLs.** One rule, 24h everywhere.

## Generic surface — enforcement, not convention

Two enforcement mechanisms make this generic across current and future web sources:

1. **`@WebSourceOkHttpClient` Hilt qualifier** (mandatory). `NetworkModule` provides exactly one `@WebSourceOkHttpClient OkHttpClient` — the one with cache, `ForceCacheHeadersInterceptor(24h)`, and `OfflineStaleFallbackInterceptor` installed. `ChitankaCatalogFactory` and every future web-source factory inject via the qualifier; there is no unqualified alternative in web-source modules. A new web source physically cannot instantiate an uncached client without adding a new binding, which shows up in review.

2. **`WebSourceItemGate` as the sole item-open entrypoint** (mandatory). ViewModels for web sources never call `catalog.getItem` directly — they call `webSourceItemGate.openItem(...)`. The gate is the only sanctioned way to open a web-source item, so freshness / stale-fallback / upsert are guaranteed. `AbsCatalog` and Storyteller intentionally do not route through the gate (they have their own sync semantics).

The freshness table is keyed by `(sourceId, remoteId)` — reusable by chitanka, gramofonche (same catalog today), and any future web scraper (Gutenberg, etc.). A new web source that wants gating swaps `catalog.getItem(itemId)` for `gate.openItem(source, catalog, itemId)` at its browse-to-detail entry point.

### CI gate (deferred)

A gradle check task (`checkWebSourceCatalogsUseQualifier`) modeled after the existing `checkNoServerReferences` / `checkRiffleLogTags` — fails CI if a class in a `catalog-*` module injects an unqualified `OkHttpClient` or a ViewModel calls `catalog.getItem` on a web-source `Catalog`. Deferred until the second web source lands (Gutenberg); premature until then.

## Migration

- One Room migration bumping the DB version, creating `remote_item_freshness`.
- Follow the migration checklist in AGENTS.md: bump `@Database.version`, add `MIGRATION_N_(N+1)`, export the new schema JSON, register in `DataModule`, add a `migrationNToN1()` test to `MigrationTest.kt`, and extend `migrateFullChain`.

## Testing

- **`ForcedCacheHeaderInterceptor`** — mock a 200 response with no cache headers; assert the response coming out has `Cache-Control: public, max-age=86400`.
- **`OfflineStaleFallbackInterceptor`** — mock an `IOException` on network; assert a follow-up request with `only-if-cached` is issued, and that a genuine cache-miss propagates the original error.
- **`RemoteItemFreshness`** — within-TTL returns true; expired returns false; missing row returns false; `stamp()` upserts; `clear()` removes.
- **`WebSourceItemGate` (JVM, with a fake `Catalog` and in-memory freshness/observer)**:
  - First call → catalog invoked, row upserted, freshness stamped.
  - Second call within TTL → catalog never invoked, returns row from DB.
  - Second call after TTL, catalog succeeds → row re-upserted, freshness re-stamped.
  - Second call after TTL, catalog throws → returns stale row without stamping.
  - Second call after TTL, catalog throws, no row → propagates error.
  - `forceRefresh = true` → catalog invoked even inside TTL.
- **`MigrationTest.migrationNToN1()`** — new table exists, primary key correct, prior rows preserved.

## Non-goals

- No cross-user or cross-device cache sharing.
- No LRU beyond OkHttp's built-in size-based eviction. The freshness table grows only with items ever opened (negligible).
- No conditional-request revalidation — chitanka/gramofonche send no ETags / Last-Modified.
- No user-facing "may be out of date" indicator when serving stale after network failure. Cheap to add later if the offline experience needs it.
- No proactive background refresh of library items.

## Open questions

None.
