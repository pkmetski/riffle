# Web-source item cache

Every browse, tap-back-tap, author page revisit, and detail open on a web [Source] (chitanka, gramofonche, and future scrapers like Gutenberg) currently hits the upstream over the network. This wastes remote capacity and risks 429 throttling. We add a **two-layer, time-limited cache** that reduces remote load, keeps items accessible offline, and expires entries on a fixed 24 h TTL.

## The two layers

- **Layer 1 — HTTP cache (disk).** OkHttp's built-in `Cache` (10 MB) sits on a Hilt-qualified `@WebSourceOkHttpClient OkHttpClient`. Two interceptors: a `ForceCacheHeadersInterceptor(maxAgeSeconds = 86_400)` that rewrites upstream responses to `Cache-Control: public, max-age=86400` (chitanka and gramofonche send no cache headers of their own), and an `OfflineStaleFallbackInterceptor` that retries with `Cache-Control: only-if-cached, max-stale=Int.MAX_VALUE` on `IOException` so any previously-cached copy is served regardless of age. This covers listing pages, connectivity pings, and within-session dedup for any URL a web source fetches.
- **Layer 2 — Room freshness gate on parsed detail.** A new `remote_item_freshness (source_id, remote_id, last_fetched_at, PK(source_id, remote_id))` table records when each item was last successfully fetched. A `WebSourceItemGate` service in `core:data` composes `Catalog + LibraryObserver + RemoteItemFreshness + LibraryItemUpserter` — it reads the persisted library-item row and short-circuits `catalog.getItem` entirely when the row is `< 24 h` fresh. If the row is expired and the fetch fails, the stale row is returned as an offline fallback; only a genuinely-uncached item propagates the error.

Both layers share the same 24 h TTL. TTL governs when we *prefer* fresh over cached — it never gates *access* to items the user has previously opened.

## Corollaries

- **Enforcement is structural, not conventional.** Two hard-to-bypass mechanisms make Layer 1 and Layer 2 automatic for every current and future web source:
  1. `@WebSourceOkHttpClient` is the only qualifier that vends an `OkHttpClient` inside web-source modules. Web-source factories inject via the qualifier; there is no unqualified alternative in `catalog-*` modules to reach for. A new web source physically cannot instantiate an uncached client without adding a new Hilt binding, which shows up in review.
  2. `WebSourceItemGate.openItem` is the sole entrypoint for opening a web-source item from a ViewModel — `catalog.getItem` is never called directly on a web-source catalog. The gate is the only sanctioned way to open a web-source item, so freshness check + stale-fallback + Room upsert + freshness stamp are guaranteed at the one place that matters. `AbsCatalog` and Storyteller do not route through the gate — they have their own sync semantics (ADR 0030, ADR 0032-pre-warm).
- **Repository-layer gate, not catalog base class.** An earlier draft placed the freshness check inside a `WebScrapedCatalog` abstract class that Chitanka would extend. That doesn't fit: `Catalog` implementations live in `core:catalog-*` modules that intentionally don't depend on `core:data`, so they cannot read the persisted library-item row. Keeping `Catalog.getItem` pure — always fetch fresh, mirroring `AbsCatalog` — and letting the repository compose caching above it is the correct layering.
- **Cache dir isolation.** The Cache is rooted at `context.cacheDir / "web-source-http"` so system-triggered cache clears (Android Settings → Clear Cache) discard it cleanly. No user data lives there — only raw HTML.
- **Pull-to-refresh is the escape hatch.** Detail-screen pull-to-refresh calls `openItem(..., forceRefresh = true)`, which skips the freshness check and refetches. Listing-screen pull-to-refresh adds `Cache-Control: no-cache` to the OkHttp request, which forces revalidation past Layer 1. Cache-clear-all is not a user-facing action; there is no "flush cache" button.
- **No background refresh, no cross-device sync, no proactive eviction.** The OkHttp `Cache` evicts by size (LRU up to 10 MB). The `remote_item_freshness` table grows only with items ever opened — bounded by user behaviour, negligible in size, no GC needed.
- **Chitanka's `getItem` stays a pure scraper.** No change to the Catalog contract or to `ChitankaCatalog` internals; the ViewModel change is `catalog.getItem(item.id)` → `webSourceItemGate.openItem(source, catalog, item.id)` at the single call site in `ChitankaBrowseViewModel.openDetail`. The freshness table is populated on that path; subsequent detail reopens hit Room.

## Scope of the first change

Ship the migration to database version 54 (`remote_item_freshness` table + DAO + `MIGRATION_53_54`), the `@WebSourceOkHttpClient` qualifier with cache and both interceptors wired in `NetworkModule`, the `RemoteItemFreshness` Room service, and the `WebSourceItemGate` in `core:data`. Refactor `ChitankaCatalogFactory` to inject the qualified client and `ChitankaBrowseViewModel.openDetail` to route through the gate. Preserve today's stale-fallback semantics ("navigate anyway if enrichment fails") via the gate's own error branch. Tests: interceptor unit tests, gate unit test with fakes, freshness Room test, and `MigrationTest.migration53To54` following AGENTS.md.

The CI gate (`checkWebSourceCatalogsUseQualifier`) is deferred until a second web source ships — with only one implementation the type/DI system already does the enforcement.

## Amendments to existing ADRs

- **[ADR 0042]** (Chitanka Source): its corollary about "browse pages are Room-cached with a 24 h TTL, keyed by `(sourceId, libraryId, facetKey, page)`. Detail pages (`getItem`) same" was aspirational and never implemented. This ADR delivers the caching promise, but via OkHttp's disk cache for browse pages (10 MB LRU, 24 h TTL, offline-stale fallback) and via the Room `remote_item_freshness` gate for detail pages. Both surfaces retain the offline-browse guarantee: `ChitankaCatalog`'s `OfflineBrowseCapability` claim remains valid — previously-visited listing pages and item details are both served from local storage when the network is unavailable.
