# ADR 0023 — The Storyteller synced bundle is the Readaloud audio source

**Status:** Accepted

## Context

Issue #37 (Readaloud playback) was specified against an assumed two-artifact model: a
text-only **EPUB bundle** plus a separate **audiobook bundle** served as a Readium audiobook
archive (`application/audiobook+zip`), each downloadable independently.

Probing the development Storyteller server (`http://media-server:8001`, `GET /api/books/{id}/synced`)
shows the real shape:

- There is exactly **one** downloadable bundle per book: `GET /api/books/{id}/synced`.
- It returns `Content-Type: application/epub+zip`, ~315 MB for *The Martian* — i.e. an EPUB 3
  that already contains the text, the audio resources, and the `.smil` Media Overlays.
- `Accept: application/audiobook+zip` is ignored; no separate audiobook archive endpoint
  exists (`/audio`, `/audiobook`, `/manifest.json`, … all 404).
- The endpoint **does** honour HTTP `Range` (`Accept-Ranges: bytes`, `206 Partial Content`),
  so a resumable download is achievable.
- Reading position lives at `GET/PATCH /api/v2/books/{id}/positions` as a Readium `Locator`
  plus a millisecond `timestamp`.

The existing reader (#35) already treats `/synced` as *the* EPUB: it caches small bundles on
open and requires an explicit Download for large ones (`EpubRepositoryImpl`).

## Decision

Riffle treats the Storyteller **synced bundle as the single source of both the readable EPUB
and the Readaloud audio.** There is no separate audio download.

- **`AudiobookBundleDownloader`** performs a **resumable, progress-reporting** `Range` download
  of `/synced` into the permanent Downloads area. It sends `Accept: application/audiobook+zip`
  for forward-compatibility with any Storyteller version that may content-negotiate, but accepts
  `application/epub+zip`. This is the AC-mandated upgrade over the stream-only `EpubBundleFetcher`.
- **"Download readaloud audio (X GB)"** downloads/promotes the synced bundle to Downloads. "X GB"
  is the probed `Content-Length`. Because the synced bundle *is* the EPUB, "fetch the EPUB bundle
  first if absent" collapses to "fetch the synced bundle once".
- **Playback** reads the `.smil` overlays and audio resources directly out of the downloaded EPUB
  zip — no extraction to a second on-disk copy.
- **Cache cap / LRU** governs *auto-cached* synced bundles in the Cached area; bundles in permanent
  Downloads are never evicted.

## Consequences

- "EPUB bundle present" and "audio bundle present" are the same predicate for Storyteller books.
- The user-facing two-action framing in the issue is preserved in spirit: the reader's headphones
  affordance offers to download the synced bundle when it is absent, with size + Wi-Fi-only gating.
- If a future Storyteller release ships a genuinely separate `application/audiobook+zip`, only
  `AudiobookBundleDownloader`'s URL/Accept handling changes; the rest of the pipeline (SMIL parse,
  track, coordinator, player) is already archive-agnostic.
