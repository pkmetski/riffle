# ADR 0041 — Annotations view is an elided reader over the local annotation store

**Status:** Proposed

## Context

The reader's [Highlight]s and their attached [Note]s already accumulate as a durable per-user body of work in the local Room [Annotation] store. Today they are surfaced only inside the source reader — as decorations over the ABS EPUB and in an in-reader annotations list. There is no way to browse them across books, no way to open "just my highlights of *Dune*" without opening *Dune* itself, and no way to re-read them once the source EPUB is uncached or the ABS Library Item is deleted server-side.

The user's brief was a top-level "Annotations" surface — a drawer entry listing every book with highlights, and, on tap, a reader-shaped view showing only those highlights. Multiple foundational choices sit inside that brief and pull against each other; this ADR records the three that are hardest to reverse.

**Decision axis 1 — is the elided view rendered from the source EPUB, or from the annotation store?** [Highlight]s carry their `textSnippet` on the row (see ADR 0024 and the Annotation glossary entry — the snippet is stored explicitly as a re-anchoring aid). The elided view can therefore be built from annotation data alone, or it can open the source EPUB and hide non-highlighted runs. The two look similar at first glance but diverge sharply on offline behaviour, on what happens after the source is deleted, and on which coordinate system "position in the elided view" belongs to.

**Decision axis 2 — does this reading surface participate in [Progress Sync] and [Reading Session]?** Every other reader surface in Riffle either (a) drives the book's canonical position and pushes it to ABS's `mediaProgress.ebookLocation`, or (b) opens a Reading Session that feeds the ABS `me/sessions` stats endpoint. The elided view is reader-*shaped* — same gestures, same formatting knobs — but not reading-*of-the-book*; the "position" it tracks is highlight-index-N, not a CFI in the source publication.

**Decision axis 3 — is this a new screen or a new mode of the existing reader?** The elided view needs paginated / vertical / continuous rendering, [Formatting Preferences], the [Table of Contents (TOC)] transformation, [Book Search], [Cadence], [Auto-Scroll], [Immersive Mode], [Volume Key Navigation], [Screen Wake Lock], and the highlight action sheet. All of that lives in `EpubReaderScreen` and `EpubReaderViewModel` today. A sibling screen would need to duplicate all of it and drift.

The three axes are tightly coupled — the choices propagate into the same architectural fork — and are captured here jointly.

## Decision

The **Annotations View** is a [Navigation Drawer] entry backed by the local [Annotation] store, and it opens the source book into an **elided reader** that is a new *source mode* of the existing `EpubReaderScreen`, not a sibling screen.

Three concrete positions:

1. **Rendered from annotation data alone.** The elided document is built from `AnnotationDao.observeForItem(...)` rows — snippets, notes, `chapterHref`, `spineIndex` — with no dependency on the source EPUB being cached, downloaded, or even still present on ABS. Chapter titles come from the local `library_items` row when available and degrade to `chapterHref` basename → "Chapter N" otherwise. Highlights of a book that no longer exists on ABS remain readable indefinitely.

2. **No [Progress Sync] and no [Reading Session].** The elided reader does not open a Reading Session against the ABS item, does not push `ebookLocation` or `ebookProgress`, and does not participate in the two-peer sync cycle. It never appears in the [Home Tab]'s In Progress section. Resume position (last-viewed highlight per book) is local-only and per-device.

3. **A `ReaderSource` flag on `EpubReaderScreen` / `EpubReaderViewModel`, not a sibling screen.** The VM branches at three known points: the `Publication` load (a `HighlightsPublicationFactory` synthesises an in-memory EPUB-3 whose spine holds only chapters-with-highlights and whose HTML paragraphs are the snippets in CFI order with notes as inline `<aside>`s), the Progress Sync entry points (gated off when `source = Highlights`), and the highlight-creation gestures (suppressed — there is no unhighlighted text to select). Everything else — pagination modes, formatting preferences, TOC, search, Cadence, Auto-Scroll, Immersive Mode, Volume Key Navigation, Screen Wake Lock, and the highlight action sheet — reuses the same code paths untouched. [Readaloud] and the [Chapter Navigation Rail] are hidden in Highlights mode.

## Consequences

- The Annotations View is the first Riffle reading surface whose durability exceeds the source EPUB's. This makes annotations a *first-class local artefact* rather than a decoration on the source, and closes the loop opened by ADR 0003 (local-first annotations) at the UX layer.
- Fetching / caching / decoding the source EPUB is not on the critical path for opening the Annotations View or the elided reader. Cold-open latency should be roughly the cost of a Room query plus in-memory Publication synthesis, not the cost of unzipping an EPUB.
- The reader ViewModel grows one new dimension of state (`source: ReaderSource`), and every future reader change must decide how it interacts with `source = Highlights`. The suppression points (Progress Sync, Reading Session, highlight creation) are the known deltas; any new behaviour that shares those semantics should be gated on `source = FullBook` for consistency.
- The "Open in book" affordance on a rendered highlight is the one bridge between the elided view and the source reader. When the source is unavailable — offline and uncached, or the ABS Library Item is gone — the elided reader still functions; only that one affordance shows a "Book not available" message.
- The elided reader is text-only in v1: [Readaloud] is hidden because its SMIL sidecar addresses spans in the source EPUB's DOM, not the synthesised one, and re-anchoring is a real feature rather than a config toggle.
- Exact-CFI duplicate highlights are prevented at write time (`EpubReaderViewModel.createHighlight` deletes overlapping highlights in the same chapter via `highlightOverlapsAtSamePosition`, tested in `HighlightOverlapTest`), so the elided renderer assumes zero duplicates. Rare cross-device duplicates from sync races are accepted as cosmetic doubling rather than resolved at render time.
- PDF books are format-neutrally excluded: the list query filters `WHERE type='HIGHLIGHT' AND deleted=false`, and [Annotation]s are anchored on EPUB CFI (ADR 0024). Adding a PDF-highlight anchor variant later is additive — the drawer entry, the list, and the sort key stay unchanged.

## Alternatives considered

- **Ghosted rendering: open the source EPUB and CSS-hide non-highlighted runs.** Rejected: it defeats the "just the highlights" pitch (paginated books become long runs of near-empty pages between highlights), it couples the view to the source being available (breaking the local-first promise), and it collapses the coordinate ambiguity between "elided position" and "source CFI" into the source's canonical position — contaminating [Progress Sync].

- **Sibling screen (`AnnotationsReaderScreen`) with its own ViewModel.** Rejected: every reader capability we want in the elided view (paginated/vertical/continuous, formatting prefs, TOC, search, Cadence, Auto-Scroll, immersive, volume nav, wake lock, highlight action sheet) already exists in `EpubReaderScreen`. Duplication cost is very high and drift is guaranteed — every future reader improvement would need to be applied twice.

- **Count elided reading as a [Reading Session].** Rejected: reviewing your own highlights is not what ABS's stats model measures. Counting it silently double-credits anyone who both reads and re-reviews. Being honestly excluded from stats is better than being dishonestly inflated.

- **Push a "highlights-view position" to Progress Sync.** Rejected: the elided document's coordinates are highlight-index-N, not a CFI in the source publication. Progress Sync's model assumes one canonical position per book; injecting a second coordinate system breaks its invariants and would silently corrupt `ebookLocation`.

- **Server-scope aggregation across all configured [ABS Server]s.** Rejected: annotations are already Server-scoped (per the [Server] glossary entry and [Annotation Sync]'s per-server merge model). A cross-server view would need a new identity scheme for "which book on which server" that Riffle otherwise avoids. The Server Switcher swaps the Annotations View contents, mirroring how Libraries, Downloads, and every other Server-scoped surface behaves.

- **View-time dedup with a disambiguation sheet.** Discussed and rejected once code inspection confirmed that dedup is enforced at write time (`highlightOverlapsAtSamePosition`, tested). The rare cross-device duplicate is accepted as cosmetic; any cleanup belongs in the sync/merge layer (ADR 0034), not in a render pass.
