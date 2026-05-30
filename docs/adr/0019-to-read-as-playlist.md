# ADR 0019 — "To Read" implemented as a name-matched ABS Playlist

**Status:** Accepted (supersedes ADR 0018)

## Context

ADR 0018 implemented "To Read" as an ABS Collection named `To Read`, looked up by name. After shipping we observed that ABS Collections are **library-scoped, not user-scoped**: the `GET /api/libraries/:id/collections` endpoint returns the same collections to every authenticated user, and mutations are visible to all users with access to that library. Two accounts on the same server therefore shared a single "To Read" list, and the ADR-0018 Read→remove-from-To-Read invariant leaked across users.

ABS has one per-user, server-persisted, arbitrary-book-list mechanism: **Playlists**. Playlists are scoped to `(userId, libraryId)`. The API endpoints (`GET /api/libraries/:id/playlists`, `POST /api/playlists`, `POST /api/playlists/:id/item`, `DELETE /api/playlists/:id/item/:libraryItemId`) accept any library item, including ebook-only items.

## Decision

**Implement "To Read" as a regular ABS Playlist named `To Read`, looked up by name and find-or-created on first use.** The find-or-create-by-name pattern from ADR 0018 carries over unchanged; only the backing storage flips from Collection to Playlist.

The Read→remove-from-To-Read invariant (ADR 0018 rule 2) is preserved against the playlist instead of the collection.

In-memory cache only (no Room table). A `MutableStateFlow<Map<libraryId, ToReadSnapshot>>` in `ToReadRepositoryImpl` holds the playlist id and member item-ids per library. `refresh(libraryId)` fetches the playlist and updates the snapshot; mutations are optimistic and revert on failure. Both `LibraryItemsViewModel.init` and `LibraryItemDetailViewModel` call `refresh` to keep the cache warm. Tradeoff: cache is empty after process death until the first refresh — acceptable because the only consumers (tab + detail screen) call `refresh` before they read.

The list is surfaced as a new "To Read" tab in the library screen, positioned between Home and Series. The tab content is a grid of `LibraryItem`s, derived by joining the in-memory item-ids with the existing `observeAllBooks` Room cache.

## Alternatives considered

**Per-user-named Collection** (e.g. `To Read — {username}`). Rejected: clutters the Collections tab in the ABS web UI, exposes usernames to other users browsing collections, and still rides on library-global storage so a user with permission could mutate another user's list out-of-band.

**Local-only Room storage keyed by (serverId, userId, itemId).** Rejected for this fix: invisible to the ABS web UI (loses the cross-client visibility property ADR 0018 valued), doesn't sync across Riffle installs without new sync infrastructure. Still a reasonable option if/when a unified sync layer lands.

**Adding a Playlist Room cache symmetric to Collections.** Rejected for this fix: requires a new entity, DAO, migration, and refresh plumbing. Disproportionate scope for an asap correctness fix. In-memory caching covers the current call sites; Room persistence is a follow-up if To Read needs to survive process death (e.g. for an offline-first widget).

## Consequences

- **Per-account isolation.** Each ABS account on the same server now has its own To Read list. Cross-account leak fixed.
- **Visible in the ABS web UI under Playlists.** The web UI renders playlists with audio-playback affordances even for ebook-only items. This is cosmetic and accepted as the cost of using the only per-user-scoped ABS mechanism that holds arbitrary book lists.
- **Legacy "To Read" Collections on existing servers are abandoned.** Users with pre-ADR-0019 history have a stranded `To Read` Collection visible in the ABS web UI's Collections tab. The app does not delete it, migrate from it, or read from it. Users may delete it manually.
- **One network round-trip per library entry and per detail-screen open.** Both ViewModels call `refresh` before reading; the cache is shared across the To Read tab and the detail screen for the lifetime of the process.
- **To Read tab is a new top-level surface in the library view.** Lives between Home and Series. Empty state reads "Nothing in To Read".
- **Rename behaviour matches ADR 0018** — a user-side rename of `To Read` creates a duplicate on next toggle.
- **Empty playlists are auto-deleted by ABS.** Removing the last item from the playlist makes ABS delete the playlist itself. The repository handles this transparently by clearing its cached `playlistId` whenever a successful remove empties the cache; the next add creates a fresh playlist. No empty tile is left in the user's Playlists tab.
- **Offline taps still fail loudly.** Same behaviour as ADR 0018 — no queueing, no silent retry.
