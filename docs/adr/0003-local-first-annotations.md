# ADR 0003 — Local-first annotations with deferred sync

**Status:** Accepted

## Context

Highlights, Notes, and Bookmarks need persistent storage. The ABS server has a bookmarks API but it was designed for audiobook timestamps and does not natively store EPUB text annotations or highlights. Options for cross-device sync via ABS custom metadata fields are fragile and unsupported.

## Decision

Annotations are stored locally as the primary store. The local schema is designed to be sync-ready (stable CFI-based identifiers, timestamps, device origin) so that server-side sync can be added later without a migration.

Bookmarks (position-only, no text selection) may additionally be pushed to ABS's existing progress/bookmark API as a lightweight workaround for cross-device position sharing.

Cross-device annotation sync is explicitly deferred until ABS adds native support.

## Consequences

- Annotations (Highlights, Notes) do not roam between devices in v1.
- Local database schema must include fields sufficient for future sync: CFI anchor, creation timestamp, last-modified timestamp, device ID, sync status flag.
- Users who care about annotation portability can use the export feature (future: JSON or standard EPUB annotations format).
