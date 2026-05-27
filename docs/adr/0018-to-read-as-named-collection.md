# ADR 0018 — "To Read" implemented as a name-matched ABS Collection

**Status:** Accepted

## Context

Riffle needs a per-book "want to read" / wishlist toggle on the Library Item Detail Screen. The user wants a single tap to queue a book and a second tap to unqueue it.

Audiobookshelf has no native "to read" / "want to read" / "wishlist" endpoint. The closest mechanisms it exposes are:

- **Collections** — user-defined, unordered groupings of Library Items scoped to a single Library, with create/add/remove endpoints.
- **Playlists** — audio-ordering oriented; not appropriate for ebooks.
- **Media Progress** — only models started/in-progress/finished; no "queued but not started" state.

A "to read" list is conceptually different from a generic Collection: it has implicit user intent ("queued"), a single canonical instance per Library, and lifecycle rules tied to reading state (a finished book should not stay queued). A generic Collection is open-ended user grouping with no intent and no app-managed lifecycle.

This raises a design choice: model "To Read" as a new first-class domain concept (e.g. "Reading Queue") with its own storage and a hidden, reserved-name backing collection, or reuse Collection directly with a conventional name.

## Decision

**Implement "To Read" as a regular ABS Collection named `To Read`, looked up by name and find-or-created on first use.** The "To Read" entry in `CONTEXT.md` documents the rules; Collection's definition is otherwise unchanged.

Two app-managed rules apply on top of normal Collection behaviour:

1. **Find-or-create by name on every toggle.** The app does not store the collection ID locally. If the lookup finds a Collection named `To Read` in the current Library, that is the To Read list. If none exists, the toggle-on path creates it.
2. **Read transitions remove from To Read.** Any transition of a Library Item to the Read state — manual mark-read, or future auto-finish detection — removes it from the "To Read" Collection in the same Library. The reverse coupling is not enforced: toggling To Read on a Read book leaves the Read flag alone.

Mutations are optimistic and in-memory: tap flips the icon, fires the request, reverts with a snackbar on failure. There is no durable mutation queue, and offline taps fail with a snackbar.

## Alternatives considered

**New "Reading Queue" domain concept with a hidden reserved-name Collection** (e.g. `__riffle_to_read`, filtered out of the Collections Tab). Rejected: it duplicates a concept ABS already exposes, makes the list invisible from other ABS clients (web UI, third-party readers) where users reasonably expect to see and edit it, and forces filtering logic across every Collection-aware UI surface. The "domain concept distinct from its backing storage" framing turned out to be over-engineering for a list whose behaviour barely diverges from a regular Collection.

**Store the Collection ID locally after first creation** (per Server + Library), instead of looking up by name. Rejected: requires a new local table and migration, introduces a dangling-pointer failure mode when the user deletes the Collection on the server, and the rename edge case it solves (server-side rename creating a duplicate on next toggle) is rare and self-correcting — a rename is a reasonable user signal that "this is no longer my To Read list."

**Symmetric Read↔To Read coupling** (toggling To Read on a Read book also clears Read). Rejected: a user re-queueing a finished book is a legitimate re-read signal. Clearing the Read flag silently would destroy reading-completion history. The asymmetry — "becoming Read removes from To Read, but adding to To Read does not unmark Read" — preserves the legitimate state ("Read and queued for re-read") while still enforcing the natural invariant on the only path that produces a contradiction in plain language ("this is finished but also queued").

**Mutually-exclusive button states** (disable the To Read button when a book is marked Read, and vice versa). Rejected for the same re-read reason, and because it requires the user to perform two taps (unmark Read → tap To Read) to express what should be a single intent.

**Durable mutation queue covering progress + collection membership + future Highlights/Bookmarks/Notes.** A real architectural option worth pursuing later (especially before Highlights ships, which will need offline durability). Not adopted here because To Read alone does not justify the new infrastructure, and folding it in would couple this feature's timeline to a larger sync redesign. Flagged as a known future direction.

## Consequences

- **The "To Read" Collection is visible in the Collections Tab.** The user can browse it, tap into it, and even add or remove books manually via the ABS web UI. This is treated as a feature, not a leak: cross-client visibility is part of the value.
- **Empty "To Read" Collection is left in place** rather than deleted when the last book is removed. An empty tile may appear in the Collections Tab; this is mild visual cost and avoids appear/disappear churn on other clients.
- **Rename-creates-duplicate is an accepted edge case.** If the user renames `To Read` on the ABS web UI, the next toggle creates a new `To Read` Collection alongside the renamed one. The app makes no attempt to detect or repair this.
- **Per-Library, not global.** A user with multiple Libraries on the same Server has one `To Read` Collection per Library. The toggle on a book affects only the Collection in that book's Library.
- **The Read→To Read invariant is enforced on the Read→true edge only,** not maintained continuously. Toggling To Read on a Read book produces a state ("Read and in To Read") that violates the natural-language invariant. This is intentional: the state is a legitimate re-read intent, and the user can clear the Read flag manually if they want a clean state.
- **Offline taps fail loudly.** No queueing, no silent retry. If/when a unified mutation-sync mechanism lands, this surface can adopt it without changing user-facing behaviour.
