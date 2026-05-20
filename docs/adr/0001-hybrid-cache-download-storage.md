# ADR 0001 — Hybrid Cache + Download local storage model

**Status:** Accepted

## Context

The app needs local file access to render EPUBs via Readium. The question is what happens to that local copy after the reading session ends.

Simple alternatives considered:
- **Cache-only:** auto-populate, auto-clear. Simple, but users lose offline access unpredictably.
- **Download-only:** explicit user action required before reading. Friction-heavy; bad for casual browsing.

## Decision

Use a hybrid model with two distinct local storage tiers:

- **Cache** — app-managed, auto-populated on open, stored in a clearable cache directory.
- **Download** — user-initiated, stored in a permanent directory, never auto-cleared.

Both tiers are available during Offline Mode. The user can explicitly Download a book to guarantee offline availability regardless of cache eviction.

## Consequences

- Reading progress recorded offline must be queued and synced back to the ABS server on reconnect (Progress Sync).
- The UI may eventually distinguish cached vs downloaded books visually, but this is not required at launch.
- Cache eviction policy (size limit, LRU, etc.) is an implementation detail deferred to the build phase.
