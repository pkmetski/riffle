---
name: reference-progress-pingpong-unified-fraction
description: "Library % ping-pong on detail open/close = bulk vs per-item pulls deriving readingProgress differently; fixed via CatalogProgress.unifiedLibraryFraction() (PR #667)"
metadata: 
  node_type: memory
  type: reference
  originSessionId: e4461921-1f82-4854-8bc8-ad3919e7006e
  modified: 2026-08-05T08:10:29.488Z
---

Library card % bouncing (e.g. 5% ↔ 59%) on every detail open/close was caused by the two writers of `library_items.readingProgress` deriving different fractions: `refreshItemProgress` (detail ON_RESUME, `GET /api/me/progress/{id}`) derived `currentTime/duration`, while `refreshLibraryItems` (library ON_RESUME, `GET /api/me`) hardcoded audio fields to 0 in `AbsCatalog.pullAllProgress` and folded ABS's stored `progress` scalar into `ebookProgress`. The scalar is write-through (last client's `currentTime/localDuration` arithmetic) and can be permanently stale — data-dependent bug, invisible on healthy records.

Fixed in PR #667 (2026-08-05): `/api/me` mapping carries `currentTime`/`duration`/`isFinished` raw, and **every** writer of `readingProgress` that consumes a `CatalogProgress` must derive through `CatalogProgress.unifiedLibraryFraction()` (finished→1f, ebook>0 wins, else audio ratio, null = skip the write). If a new progress-write site appears, route it through that helper — a second divergent derivation reintroduces the ping-pong. Related: [[reference_audiobook_position_double_count]] (one way the scalar gets poisoned).
