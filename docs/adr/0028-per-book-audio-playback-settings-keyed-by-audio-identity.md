# ADR 0028 — Per-book audio playback settings, keyed by a shared audio identity

**Status:** Accepted
**Relates to:** [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md), [ADR 0021](0021-storyteller-abs-matching-with-review-queue.md), [ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md), [ADR 0025](0025-key-local-stores-by-server-and-item.md), [ADR 0026](0026-storyteller-as-settings-only-readaloud-backend.md).

## Context

The Readaloud player exposes a playback-speed control (`0.75× / 1× / 1.25× / 1.5× / 2×`). Today the speed is **runtime-only**: `ReadaloudController.PlaybackState.speed` initialises to `1f` and resets to `1×` every time the reader is opened. Nothing is persisted.

We want speed to be **configurable per book and remembered**, the same way per-book reading/formatting preferences work (`book_formatting_preferences`, device-local, nullable overrides over a global default — ADR 0025). Two constraints distinguish audio settings from formatting settings:

1. **The global default is fixed at `1×` and is not user-configurable.** Unlike formatting, there is no global audio-settings surface; `1×` is a constant baseline that a per-book override replaces.
2. **Audio settings must be shareable with the future audiobook player.** Full audiobook playback is on the roadmap. A speed chosen for a book's Readaloud should carry over to that book's audiobook, and vice-versa — they are the same listening experience to the user.

The identity question is the crux. A Readaloud is the Storyteller synced bundle (ADR 0023), identified by `(storytellerServerId, storytellerBookId)`. A book's audiobook is a **separate ABS library item** with its own `(absServerId, absItemId)`, distinguishable by `LibraryItemEntity.hasAudio == true`. Both attach to the *same* Storyteller readaloud via `ReadaloudLink` (ADR 0021). The cardinality is **1 readaloud ↔ 0–1 ebook, 0–1 audiobook**. Audiobook link/unlink is an existing user action (Settings → Storyteller Server → Readaloud matches: Confirm / Unlink / Match manually), so the linked audiobook can appear or disappear at runtime.

Reusing `book_formatting_preferences` was rejected: it is keyed by the **ABS ebook item** and mixes ebook-rendering prefs that must *not* be shared with a separate audiobook item. Audio settings need a different key and a different lifetime.

## Decision

**Persist audio playback settings in a dedicated, device-local store keyed by a single resolved "audio identity," with a fixed `1×` global default. The audio identity prefers the linked audiobook's id, so a book's Readaloud and its audiobook share one record.**

### Audio identity resolution

One resolver is the sole owner of the precedence rule. Given the ABS ebook item a reading session was opened from:

- **Audiobook linked** (the `hasAudio` sibling on the same Storyteller book) → key is the **audiobook's** `(absServerId, absItemId)`.
- **Readaloud only, no audiobook** → key is the **Storyteller** `(storytellerServerId, storytellerBookId)`.
- **(Future) standalone audiobook with no readaloud** → key is its **own** `(absServerId, absItemId)`.

Storyteller-rooted and ABS-rooted keys cannot collide because `serverId` is a distinct `servers` row (ADR 0025). The resolver is used **only** for the settings key; Readaloud *bundle* loading continues to use the Storyteller id (ADR 0023) — these are two different uses of identity and are intentionally kept separate.

### Storage

- New table `audio_playback_preferences`: primary key `(serverId, bookId)`, column `speed REAL`, foreign key `serverId → servers.id` (CASCADE). A row exists **only** when the user has overridden the default; absence ⇒ `1×`.
- Global default is the constant `DEFAULT_PLAYBACK_SPEED = 1.0f`. There is **no** global audio-settings UI.
- Domain store `AudioPlaybackPreferencesStore`: `load(identity)`, `save(identity, speed)`, `clear(identity)`, and **`rekey(old, new)`**.
- The schema is allowed to grow additional audio-setting columns later (the reason for a dedicated table rather than a column on an existing one); only `speed` is added now (YAGNI).

### Re-keying on link / unlink

The record must follow the audio identity when an audiobook is linked or unlinked. The Readaloud review repository computes the canonical key **before and after** each link/unlink mutation and migrates if it changed:

```
val before = resolveKey(storytellerServerId, storytellerBookId)
<perform link / unlink>
val after  = resolveKey(storytellerServerId, storytellerBookId)
if (before != after) audioPrefs.rekey(before, after)
```

- **Link an audiobook** → `before` = Storyteller id, `after` = audiobook id ⇒ the saved speed moves onto the audiobook id.
- **Unlink the audiobook** → `after` falls back to the Storyteller id ⇒ the speed moves back. The record is never orphaned.
- Linking/unlinking an **ebook** (no `hasAudio`) does not change the key ⇒ no migration.

This centralises the precedence rule in `resolveKey` and avoids per-operation special-casing.

### Player wiring

`EpubReaderViewModel` resolves the audio identity on open, `load()`s the saved speed, and applies it to the controller when playback is prepared (replacing the hardcoded `1f`). `setSpeed(speed)` additionally `save()`s under the resolved identity. The player UI (`ReadaloudPlayerUi.kt`) is unchanged.

## Consequences

- **Speed persists per book and survives sessions**, instead of resetting to `1×` on every open.
- **Readaloud and the (future) audiobook share one setting automatically.** When the audiobook player lands, it resolves to the same audiobook-id key and inherits the already-saved speed with no backfill. The dedicated store and `rekey` seam are the audiobook player's persistence layer too.
- **A new Room migration is required:** bump the `@Database` version, add `MIGRATION_N_(N+1)` creating `audio_playback_preferences`, register it in `DataModule`, and add the corresponding `MigrationTest` cases (per `CLAUDE.md`).
- **The review repository gains a dependency on the audio-prefs store and `LibraryItemDao`** (to read `hasAudio`) so it can re-key on link/unlink. Hook points: `createUserConfirmedLink`, `unlinkAbsItem`, `unlinkBook`.
- The existing inline `audioBookId = link?.storytellerBookId ?: itemId` logic in `EpubReaderViewModel` is **not** repurposed for the settings key; the settings key comes from the resolver, while bundle loading keeps its current Storyteller-id behaviour.
- Settings are **device-local and never synced** (same posture as `book_formatting_preferences`).

## Alternatives considered

- **Add a `speed` column to `book_formatting_preferences`.** Rejected: that table is keyed by the ABS ebook item, so a separate audiobook item would not share the setting; it also conflates device-local ebook-rendering prefs with audio prefs meant to be shared across items.
- **Key audio settings by the Storyteller readaloud id in all cases.** Rejected: it shares correctly today but puts the canonical key on the Readaloud rather than the audiobook, which is the durable primary audio source when present. Per the requirement, when an audiobook exists the id must be the audiobook's.
- **Introduce a generic per-book key/value settings table.** Rejected as over-engineered for a single float (YAGNI); the dedicated table can grow columns when real additional audio settings appear.
- **A user-configurable global default speed** (like formatting). Rejected per the requirement: the global default is a fixed `1×`.
- **Resolve the settings key fresh on every open without migrating on link/unlink.** Rejected: when an audiobook is linked the key changes and the previously saved speed would silently appear lost; the record must be migrated so it is never orphaned.
