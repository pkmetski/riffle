---
name: reference-abs-playlist-api
description: "Verified ABS playlist endpoint shapes and the empty-playlist auto-delete behaviour, including the docs/source discrepancy"
metadata: 
  node_type: memory
  type: reference
  originSessionId: ebff92c3-2110-4500-ae8e-b071cfc624e2
---

ABS playlist HTTP endpoints, verified against the live server at `http://media-server:13378` on 2026-05-30 (test account, books library). See [[reference-abs-server]] for credentials.

- `GET /api/libraries/:libraryId/playlists?limit=500` — returns the requesting user's playlists in that library. Each item shape: `{ libraryItemId, libraryItem: { id, libraryId, media: {...} } }`.
- `POST /api/playlists` — body `{ libraryId, name, items: [{ libraryItemId }] }`. Accepts `items: []` to create an empty playlist.
- `POST /api/playlists/:id/item` — body `{ libraryItemId }`. **Singular `/item`.**
- `DELETE /api/playlists/:id/item/:libraryItemId` — **singular `/item`.**

**Docs are wrong about the path.** api.audiobookshelf.org says `/items` (plural). The live server returns 404 for plural and 200 for singular. Always use singular; trust the live server / source over the published docs.

**Empty playlists are auto-deleted server-side.** Removing the last item from a playlist makes ABS delete the playlist entirely — subsequent `GET /api/playlists/:id` returns 404. Any client caching `playlistId` must invalidate it when a successful remove empties the cached itemset, or the next add will POST to a dead id.

Relevant Riffle code: `ToReadRepositoryImpl` (see [[project-riffle-status]] for current feature state).
