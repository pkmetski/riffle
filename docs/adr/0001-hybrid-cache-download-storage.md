# ADR 0001 — Hybrid Cache + Download local storage model

**Status:** Accepted

## Context

The app needs local file access to render EPUBs via Readium. The question is what happens to that local copy after the reading session ends.

Simple alternatives considered:
- **Cache-only:** auto-populate, auto-clear. Simple, but users lose offline access unpredictably.
- **Download-only:** explicit user action required before reading. Friction-heavy; bad for casual browsing.

## Decision

Use a hybrid model with two distinct local storage tiers:

- **Cache** — app-managed, auto-populated on open, stored in Android's cache directory (`getCacheDir()`). Evicted automatically by the OS when storage is low.
- **Download** — user-initiated, stored in a permanent directory, never auto-cleared.

Both tiers are available during Offline Mode. The user can explicitly Download a book to guarantee offline availability regardless of cache eviction.

### Promotion pathway

A cached item can be promoted to a Download without re-fetching from the network. The file is copied to the permanent downloads directory and deleted from the cache directory. From the user's perspective this is the same "Download" action — the app handles whether to fetch or promote transparently.

### Storage abstraction

Both tiers are backed by the same `EpubLocalStore` interface (get, save, delete, clear), with two DI-injected instances pointing at their respective directories. Implementations are identical; only the directory differs.

### Local availability flags

`isCached` and `isDownloaded` are derived at runtime from file existence — not persisted in the database. Either directory can be modified externally (OS eviction, file manager), so the DB cannot be the source of truth.

### File lookup order

When opening a book: downloads directory first, cache directory second, network last.

### Downloads section

A dedicated screen lists all locally available items by scanning both directories and cross-referencing item IDs with the database for metadata. Includes actions to clear the cache and clear all downloads (the latter requires user confirmation).

### UI states

Three distinct local states are surfaced to the user: not local, cached (available but may be evicted), downloaded (permanent). These indicators appear on item detail and the downloads section — not in the library list, to avoid per-item file existence checks during list rendering.

## Consequences

- Reading progress recorded offline must be queued and synced back to the ABS server on reconnect (Progress Sync).
- `EpubRepository` exposes a `downloadEpub()` operation alongside `openEpub()` — the former stores directly to the downloads directory without opening the reader.
- Cache eviction is OS-managed. The app does not implement its own eviction policy.
