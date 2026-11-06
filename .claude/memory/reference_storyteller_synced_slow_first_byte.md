---
name: reference-storyteller-synced-slow-first-byte
description: Storyteller /synced is slow-to-first-byte (lazy full-bundle gen); it can hang the readaloud streaming sidecar fetch
metadata: 
  node_type: memory
  type: reference
  originSessionId: ef3735de-dfbc-403b-b322-1ea8ad444155
---

Storyteller's `GET/HEAD /api/books/{id}/synced` is **slow-to-first-byte**: it appears to lazily
generate the entire aligned bundle (e.g. The Martian = 313 MB) before serving *any* response —
even a `Range: bytes=0-0` or a `HEAD`. Observed on the test server (`100.102.186.26:8001`, via the
[[reference-emulator-tailscale-bridge]]) to **never respond within 90s** for The Martian and >30s for
4 other books (Children of Dune, 1984, Elegant Universe), all `processing_status: null`. So the
"cheap ~1 MB sidecar range-extraction" of ADR 0028 only works when `/synced` is already warm.

This is what made streaming readaloud "playback doesn't start" (verified on AVD, 2026-06-10): the
streaming session build (`ReadaloudStreamingSessionFactory.tryBuild`) awaited the sidecar size-probe
on a client with `callTimeout(0)` and hung forever behind the Play tap. Fixed by giving the sidecar
probe + range reads a bounded client (`StorytellerBundleApiImpl.sidecarClient`, 15s callTimeout) so a
cold `/synced` fails fast → `tryBuild` returns null → falls back; plus a play-bar message instead of a
silent no-op. The explicit full `downloadBundle` keeps its unbounded timeout (the user opted into the
wait). See [[project-readaloud-audiobook-sync-gap]] and ADR 0028.

The fast alternative not yet used: `/api/v2/books/{id}` returns the readaloud Readium **manifest**
quickly (already used for fingerprinting) — a future fix could source the SMIL/text from there or a
pre-warmed sidecar instead of range-extracting the cold `/synced`.
