# ADR 0025 — Key Library Items by (serverId, itemId) end-to-end

**Status:** Accepted — **design A** (composite `(serverId, itemId)` PK across the schema). Tracked in [issue #81](https://github.com/pkmetski/riffle/issues/81), which weighed design **A** against design **B** (namespaced id at ingestion).

## Context

Riffle keys local Library-Item data by **item id alone**, on the unstated assumption that item ids are unique across every configured Server. That assumption is false.

- **Storyteller** item ids are stringified sequential integers that **restart per Server** (`LibraryRepositoryImpl`: `book.id.toString()` → `"1"`, `"2"`, …). Two configured Storyteller Servers both emit `"1"`, `"2"`, `"3"`.
- **ABS** item ids are UUID-style strings, so ABS-vs-ABS and ABS-vs-Storyteller never collide.

So with **two Storyteller Servers**, server A's book `"1"` and server B's book `"1"` are the same id. This collides in **two** places, not one:

1. **The file stores** (`LocalStore` / `LocalStoreImpl`) key files by item id (`1.epub`). Server B active → a lookup for `"1"` returns server A's bytes.
2. **The database.** `library_items` has **`@PrimaryKey val id` (itemId) and no `serverId` column**; `getItem` is `SELECT … WHERE id = :itemId`; inserts use `OnConflictStrategy.REPLACE`. Server B's book `"1"` **overwrites** server A's row. Metadata lookups (including the Downloads screen's `getItem(itemId)`) then resolve to whichever row survived.

This is why a file-store-only re-key is a **half-measure**: separating files by `(serverId, itemId)` while the DB still merges the two books on the primary key would not fix the collision and would make file-vs-metadata attribution inconsistent. `ReadingPositionStore` already keys by `(serverId, itemId)`; the item store and the `library_items` table are the outliers, and they must move together.

## Decision

Key Library Items by **(serverId, itemId)** consistently across **both** the file stores and the database — not the file stores alone.

- **File stores:** `LocalStore` methods take `serverId`; files live under `dir/<serverId>/<itemId><ext>`. A one-time on-disk migration relocates legacy flat files using each item's owning Server (resolved itemId → libraryId → `libraries.serverId`), deleting orphans.
- **Database:** `library_items` gains a `serverId` column and a composite key (`serverId`, `id`); `getItem`, item queries, and every table that references item ids are updated accordingly, with a Room schema migration. `readaloud_links` / `readaloud_candidates` / `reading_positions` / `annotations` are **already** `(serverId, itemId)`-keyed, so the change touches `library_items`, the `series_items` / `collection_items` join tables, and `book_formatting_preferences` (see below).
- **`book_formatting_preferences` is in scope, keyed by `(serverId, itemId)` — for *identity*, not sync.** Formatting (font size, theme, margins, …) stays per-device: never synced, never per-user. But the row must point at the *right* book, and once `itemId` alone no longer identifies a book on-device, two colliding Storyteller books would otherwise share one formatting row and stomp each other. Adding `serverId` to its key disambiguates which book; it does **not** make formatting a per-server feature and adds no `userId`. (Reverses the earlier "never add serverId to formatting prefs" stance, which conflated the *sync* axis with the *identity* axis.)
- `serverId` — not Server *type* and not username — is the disambiguator. Type is too coarse (does not separate two Servers of the same type); username is the wrong axis (cached bytes are book content, not user data).
- **Why A over B:** the codebase already keys four tables by `(serverId, itemId)`, so A makes `library_items` consistent with its neighbours instead of introducing a second, opposite convention; `MIGRATION_18_19` (reading_positions itemId → composite) is a proven template. B's "strip the `serverId:` prefix at every network boundary" is a stringly-typed footgun — every missed call site is a silently wrong-server request that still compiles. A's costs are concentrated and compiler-checked; B's are diffuse and runtime-checked.
- **Migration is 25 → 26.** The DB is already at v25 (the `annotations` store, `MIGRATION_24_25`, landed after this ADR was first drafted); the issue text's "v24→25" is stale.

## Alternatives considered

- **Re-key the file stores only.** Rejected: does not fix the collision because the DB primary key is still itemId; produces an inconsistent file-vs-metadata state. This was the original (too-narrow) framing of this ADR.
- **Leave keying as itemId-only, document the risk.** Tenable while no user runs two Storyteller Servers; rejected as the long-term answer because it is a real correctness bug.
- **Key by (serverType, itemId) / (serverId, username, itemId).** Rejected — see Decision.
- **Design B — namespace the id at ingestion** (`LibraryItem.id = "${serverId}:${rawId}"`, single-column PK kept, strip the prefix before every server call). Rejected — see "Why A over B" above. Smaller DB footprint, but the cost moves to the network boundary as runtime-checked prefix-stripping at every call site, against the grain of the four tables already keyed by `(serverId, itemId)`.

## Consequences

- This is a cross-cutting change (Room migration + DAO/query updates + file-store interface + on-disk migration + their tests), independent of and larger than the downloads/caching-settings consolidation. It is therefore its own change rather than bundled in.
- File stores, `library_items`, `series_items` / `collection_items`, and `book_formatting_preferences` become consistent with the `(serverId, itemId)` keying already used by `reading_positions`, `readaloud_links`, `readaloud_candidates`, and `annotations`.
- DAO queries and file-store calls that previously took `itemId` alone now also require `serverId`; callers thread it through (they already hold `activeServer.id`). Because `itemId` stays raw (design A, not B), calls *to* servers are unaffected — no prefix-stripping.
