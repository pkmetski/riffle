# Plan — O'Reilly on-demand (per-chapter) content loading

**Status:** Proposed follow-up (not started). The shipping default remains synthesize-the-whole-EPUB
with pacing/backoff/truncation-detection/fail-clean + a "Preparing book… N/M" progress screen.

## Why

O'Reilly (`SourceType.OREILLY`) currently synthesizes a complete EPUB on open: fetch metadata +
spine + file list, then **every** chapter (~40) and asset (~150) = ~190 authenticated requests,
assemble a ZIP in memory, hand a `file://` EPUB to Readium. Two structural problems remain no matter
how much we tune it:

1. **Latency** — a full build is minutes (paced), so opening a book has a long "Preparing book…" wait.
2. **Anti-abuse throttling** — ~190 rapid content requests looks like bulk download; O'Reilly starts
   returning 403s / DRM samples. The pacing/backoff/fail-clean safeguards *mitigate* but can't
   *eliminate* this, because the request volume is inherent to building the whole book at once.

O'Reilly's own web reader avoids both by fetching each section **on demand** as you read. This plan
brings the same model to Riffle for O'Reilly only.

## Goal

Open an O'Reilly book (almost) instantly and stream chapter bytes lazily as the reader renders them,
so: opening is fast, only-what-you-read is fetched (no bulk download → no throttling), and offline
becomes an explicit opt-in "Download".

## Current relevant code

- `core:catalog-oreilly/OReillyCatalog.synthesizeEpub()` — the whole-book builder (keep for Download).
- `OReillyApi` — `getJson` (metadata/spine/files) + `getContent`/`getBytes` (`?download=false` + HTML
  Accept). Already carries pacing/backoff/truncation guards.
- `OReillyCatalog.ebookDetails()` (`EbookDetailsCapability`) — already builds the cheap structure
  (spine order + titles + per-file `file_size`) from 2 JSON calls. **This is exactly the metadata the
  on-demand publication needs** — reuse it.
- Reader open path: `EpubReaderViewModel.openPublication()` → `AssetRetriever.retrieve(file://…)` →
  `PublicationOpener.open(asset)`. On-demand must offer an alternative to this file-based route.

## Architecture

1. **Build the publication *shape* from metadata (no content).**
   From spine + files list + metadata, construct in memory: reading order (hrefs + titles), the full
   resource list (path + media type), a synthetic OPF + EPUB 3 nav. Zero chapter bytes fetched.

2. **Custom lazy Readium `Container`/`Resource`.**
   A `Container` whose `Resource.read()` performs the authenticated O'Reilly fetch on access
   (`/files/{path}?download=false`, HTML Accept, reusing `OReillyApi.getContent`/`getBytes` with the
   existing pacing/backoff/truncation logic). Readium calls `read()` only for the currently-rendered
   resource (current chapter + its images/CSS). Back a small **disk cache** keyed by
   `(bookId, fullPath)` so re-reading a chapter never re-fetches. Rewrite the absolute
   `/api/v2/epubs/{urn}/files/…` asset URLs to relative on read (same rule as synthesis today).

3. **New reader open path for O'Reilly.**
   Instead of `AssetRetriever.retrieve(file://…)`, build the `Publication` directly via Readium's
   `Publication.Builder` over the custom container and hand it to the reader. Gate this on a Catalog
   capability (e.g. `LazyPublicationCapability`) so the shared reader stays source-agnostic — same
   pattern as `EbookDetailsCapability`. Fall back to the file path for every other source.

4. **Prefetch.**
   On landing in a chapter, background-prefetch the next chapter (and its assets) so most chapter
   turns are instant. Throttle prefetch with the existing pacing.

5. **Download (offline) stays a full synthesis.**
   The Download button keeps calling `synthesizeEpub()` (background, throttled, "Preparing book…"
   progress) to produce a complete offline EPUB. Reading a downloaded book opens the local file (no
   lazy container). So: Read = stream lazily; Download = full offline copy.

## User experience

- **Read** → opens in ~1 chapter-fetch (~1s), not ~190. No long "Preparing book…" wait.
- **New chapter** → brief per-chapter spinner (~1–2s), hidden by prefetch most of the time.
- **Page back** → instant (cached).
- **Never looks like bulk download** → stops tripping O'Reilly throttling.
- **Offline** → only already-read chapters unless the user taps **Download** (explicit, backgrounded,
  progress-shown) to get the whole book.

## Edge cases / decisions

- **In-book text Search (Readium `SearchService`)** searches *all* resources → would fetch the whole
  book, defeating the purpose. Decide one of: (a) search only cached chapters; (b) prompt "Download to
  search"; (c) wire O'Reilly's server-side in-book search API. **Must be decided before shipping.**
- **Positions / reading estimate** — estimate already comes from metadata (`EbookDetailsCapability`);
  Readium position/locator math must be validated across lazily-loaded resources (compute from
  declared `file_size`, matching the STORED-zip 1 KiB-position count used today).
- **CFI / annotations** — per-chapter, unaffected in principle; verify anchor round-trips.
- **Offline mode** — reading a not-downloaded book with no network fails per-chapter gracefully
  ("chapter unavailable offline") rather than at open.
- **iOS** — the lazy-container/open-path is Readium-Android-specific today; iOS needs its own when iOS
  reading exists (the fetch/cache logic stays shared in `commonMain`).

## Work breakdown (rough)

1. Metadata → in-memory publication shape (OPF + nav + resource list) — reuses `ebookDetails`. **S**
2. Custom lazy `Container`/`Resource` + `(bookId, path)` disk cache + asset-URL rewrite. **M**
3. `LazyPublicationCapability` + O'Reilly impl; reader open path that builds `Publication` from it,
   gated on the capability with file-path fallback. **L** (touches the shared reader open route)
4. Next-chapter prefetch. **S**
5. Search handling decision + implementation. **M**
6. Position/locator + CFI validation across lazy resources. **M**
7. Tests: lazy-container read/cache unit tests; on-device open-latency + chapter-turn + offline. **M**

**Overall: a scoped project (roughly L–XL), not an add-on** — it introduces a second, bespoke
publication-open route for one source. Highest-value pieces are (2)+(3); (5) is the main
feature-behavior change to flag to users.

## Recommendation

Ship the current synthesize-to-file model (done) as default. Pursue this plan as a deliberate
follow-up if O'Reilly becomes a first-class source — it's the only model that removes both the wait
and the throttling instead of mitigating them.
