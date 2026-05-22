# ADR 0008 — Last-update-wins for bidirectional progress sync

**Status:** Accepted

## Context

Progress Sync (issue #11) required a conflict resolution strategy for the case where a book's reading position differs between the local device and the ABS server (e.g. after reading on a second device). The original spec proposed a user-facing conflict prompt: show both positions in human-readable form and let the user choose which to continue from.

## Decision

Use last-update-wins with no user prompt. On every sync cycle (every ~30 seconds and immediately on reader resume) the app GETs the server's current position and compares `server.lastUpdate` against `localUpdatedAt` — a locally-persisted timestamp updated on every position change (page turn, scroll). Whichever timestamp is newer takes effect silently: if the server is newer the reader jumps to the server position; if local is newer the local position is PATCHed to the server.

The explicit conflict prompt is removed entirely.

## Alternatives considered

**User-facing conflict prompt** (original spec): show both positions and ask. Rejected because it adds friction to the common case (resuming after switching devices), which should be seamless. The scenario where the "wrong" position wins is rare and low-cost — the user can always navigate back manually.

## Consequences

- No Room migration is needed beyond adding `localUpdatedAt` (epoch millis) to `ReadingPositionEntity`.
- Sync logic is stateless per cycle — no queuing, no deferred prompts.
- If the server is unreachable the entire cycle is skipped (no blind PATCH); `localUpdatedAt` accumulates locally and is pushed on the next successful cycle.
- Clock skew between device and server is mitigated by setting `localUpdatedAt = server.lastUpdate` after every successful PATCH (using the timestamp returned in the PATCH response body).
- Applies to all reader formats (EPUB, PDF, and any future formats) via shared sync infrastructure.
