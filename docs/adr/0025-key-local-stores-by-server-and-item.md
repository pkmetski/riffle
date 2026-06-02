# ADR 0025 — Key Library Items by (serverId, itemId) end-to-end

**Status:** Proposed (not yet implemented — scoped out of the downloads/caching consolidation once the collision was found to be systemic). Tracked in [issue #81](https://github.com/pkmetski/riffle/issues/81), which also weighs design **A** (composite PK) vs **B** (namespaced id).

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
- **Database:** `library_items` gains a `serverId` column and a composite key (`serverId`, `id`); `getItem`, item queries, and every table that references item ids (series_items, collection_items, readaloud links/candidates) are updated accordingly, with a Room schema migration.
- `serverId` — not Server *type* and not username — is the disambiguator. Type is too coarse (does not separate two Servers of the same type); username is the wrong axis (cached bytes are book content, not user data).

## Alternatives considered

- **Re-key the file stores only.** Rejected: does not fix the collision because the DB primary key is still itemId; produces an inconsistent file-vs-metadata state. This was the original (too-narrow) framing of this ADR.
- **Leave keying as itemId-only, document the risk.** Tenable while no user runs two Storyteller Servers; rejected as the long-term answer because it is a real correctness bug.
- **Key by (serverType, itemId) / (serverId, username, itemId).** Rejected — see Decision.

## Consequences

- This is a cross-cutting change (Room migration + DAO/query updates + file-store interface + on-disk migration + their tests), independent of and larger than the downloads/caching-settings consolidation. It is therefore deferred to its own change rather than bundled in.
- Until done, the two-Storyteller-Server collision remains a known limitation (documented in CONTEXT.md and ADR 0023's keying notes).
- When implemented, file stores and `library_items` become consistent with `ReadingPositionStore`'s `(serverId, itemId)` keying.
