# ADR 0024 — Drop the per-Storyteller-server audio cache cap; cache eviction is OS-managed

**Status:** Accepted (supersedes the "Cache cap / LRU" clause of [ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md))

> **Note (ADR 0026):** the implicit cache-on-open path described in the Context below (`MAX_STORYTELLER_IMPLICIT_CACHE_BYTES` / `BundleTooLarge` in `EpubRepositoryImpl`) has since been removed — a Storyteller book is never opened directly, so its bundle reaches disk only via the explicit readaloud-audio download / the cross-EPUB index prerequisite fetch. The decision below (no app-managed audio cache cap) stands.

## Context

[ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md) introduced a per-Storyteller-server, app-managed LRU cap (`AudioCachePreferencesStore`, default 2 GB, enforced by `LruCacheEvictor`) over the *auto-cached* synced-bundle area, surfaced as an "Audio cache" section in global Settings.

In practice that cap governs an almost-always-empty set:

- The synced bundle *is* the EPUB *and* the audio (one file). For any real Readaloud it is hundreds of MB.
- `EpubRepositoryImpl` only auto-caches a Storyteller bundle on open when it is **under 50 MB** (`MAX_STORYTELLER_IMPLICIT_CACHE_BYTES`); larger bundles return `BundleTooLarge` and require an explicit Download.
- So every real Readaloud bypasses the evictable Cache tier entirely and lives in permanent Downloads, which the cap never touches (ADR 0023: "bundles in permanent Downloads are never evicted").

The result: a user-facing, per-server caching setting that budgets a tier no real content occupies. It is confusing (a "caching" knob for content that must be explicitly downloaded to play) and earns nothing.

Separately, the evictable Cache tier (`cacheDir/epubs`, `cacheDir/pdfs`) is Android's own cache directory, which the OS already evicts under storage pressure — exactly the model [ADR 0001](0001-hybrid-cache-download-storage.md) chose ("Cache eviction is OS-managed. The app does not implement its own eviction policy"). The audio cap was the lone exception to that principle.

## Decision

Remove the app-managed audio cache cap entirely and rely on OS-managed eviction of the cache directory, restoring [ADR 0001](0001-hybrid-cache-download-storage.md) as the single eviction model for all formats.

Deleted: `AudioCachePreferencesStore` + impl + its DataStore + DI wiring, `LruCacheEvictor` + `CachedBundle`, `ReadaloudAudioRepository.enforceCacheCap()`, and the "Audio cache" section in Settings.

There is **no app-managed cache size cap and no caching configuration** anywhere. The Downloads screen instead simply **reports** the total size of the Downloaded and Cached sections, so the user can see local usage without a knob that does nothing useful.

The 50 MB implicit-cache gate in `openEpub` is **kept** — it is a fetch-time guardrail that prevents silently pulling a multi-hundred-MB bundle when the user merely taps to read, not a caching policy.

## Alternatives considered

- **Make the cap global instead of per-server.** Rejected: a global cap over the same near-empty evictable tier is just as pointless; the problem is the tier, not the scope.
- **Keep the cap, relabel it.** Rejected: no label makes a budget over an empty set meaningful.
- **Generalize the LRU to cap the whole cache tier (all EPUBs/PDFs).** Rejected: plain EPUBs are < 10 MB, so a cap there budgets trivial amounts; the OS already evicts the cache dir.

## Consequences

- Cache eviction for all formats is OS-managed; no Riffle-managed eviction code remains.
- The "Audio cache" Settings section is gone; the only Storyteller-specific Settings surface (the per-server cap) disappears, consolidating local-storage visibility into the Downloads screen.
- The removed `AudioCachePreferencesStore`'s backing DataStore file (`audio_cache_cap`) is left in place; it becomes inert (nothing reads or writes it) and needs no active cleanup.
- The Downloads screen gains size totals (Downloaded, Cached) but no configuration.
