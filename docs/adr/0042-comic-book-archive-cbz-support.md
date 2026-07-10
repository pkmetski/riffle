# Comic Book Archive (CBZ) support

Riffle ships two reader formats today — reflowable EPUB (Readium) and fixed-layout PDF (Readium's Pdfium adapter) — and its [Readable] capability is defined as "has an ebook file (EPUB or PDF)." Users have collections of comics (`.cbz`, `.cbr`) alongside their ebooks and audiobooks, and the primary ABS instance many users run already surfaces those files in its book libraries. We add **Comic Book Archive** as the third supported format, with a purpose-built reader whose navigation clones CDisplayEx's default interaction model.

## Three architectural moves

### 1. Comics extend Readable — no new capability

Reading a comic is reading. Every Library-scoped surface — [Home Tab], [All Books Tab], [Not Started], [In Progress], [Completed], [Downloads Screen] — treats "has a file to open" as a single class. Introducing a `Viewable` capability parallel to `Readable` would double every one of those surfaces for no user benefit; the mental model is the same and the plumbing should be the same.

Consequences:

- **[Supported Formats]** grows a third entry: Comic Book Archive.
- **[Readable]** covers EPUB, PDF, **and** comics.
- **Library Item Detail Screen's Read button** routes by format — a third branch alongside EPUB and PDF.
- Features that are text-domain — **[Book Search]**, **[Annotation]s** (Highlight/Note/Bookmark), **[Readaloud]**, **[Cadence]** — remain unavailable for comics, following the same pattern that already excludes PDF from Annotations and Book Search. Comics never appear in the [Annotations View] for the same reason PDFs don't ([ADR 0024]).
- Features that are format-agnostic — [Immersive Mode], [Screen Wake Lock], [Volume Key Navigation], [Progress Sync], [Reading Session]s, [Reading Statistics] — work for comics without special-casing.

The alternative — a `Viewable` capability — was rejected because it would force parallel plumbing through the six Library tabs, the Detail Screen, the Downloads Screen, and the progress-tracking pipeline, all to model a distinction users do not perceive.

### 2. Integer page as canonical; Readium Locator JSON on the ABS wire in v1

A comic's canonical position is a **zero-based integer page index**. Page count is the number of image entries in the archive in filename-sorted order. Pan and zoom state within a page is **per-device transient** — not part of the canonical, not synced, not persisted across sessions.

**On the ABS wire, v1 uses the same shape PDF already uses:** a Readium `Locator` serialized as JSON in the ABS `ebookLocation` field, with the page index carried in `locations.position`. This is deliberately pragmatic — it reuses the existing `CatalogEbookProgressRemote` code path unchanged, ships a working two-device sync now, and defers a comic-specific wire codec until we have a concrete need to interoperate with the ABS web reader (which stores comic position as a fractional string `"0.4237..."`).

Consequences:

- No new translator ships in v1; comic sync piggybacks on the PDF path.
- Interop with the ABS web reader for comic position is **not** achieved by v1 — a user reading the same comic on Riffle and on ABS-web will see progress diverge because the two clients don't agree on the wire format. Same-Riffle-across-devices works.
- Rare page-count drift between devices (e.g., user re-downloads a re-packed archive with a different image count) produces the same failure mode PDF already has under page-index-drift; acceptable and documented.

**Follow-up (roadmap):** a `ComicPositionTranslator` at the Peer boundary — same shape as [EPUB CFI Translator] — that converts `pageIndex ↔ fraction` string using the local page count, making Riffle interop with the ABS web reader. Deferred because it requires care around the "page count not yet known locally" state (the reconciler would need to skip conversion until first open) and doesn't affect the v1 audience (single-app users).

Alternatives considered (and rejected for v1):

- **Fraction wire codec.** Rejected for v1 scope reasons above; on the roadmap.
- **Fractional canonical (0.0–1.0).** Format-agnostic on the wire but user-hostile: "Page 47 of 120" is what users think; scrubber ticks land on pages, not fractions. Rejected as canonical.
- **CFI-style opaque string.** No corresponding structure in a comic archive to anchor to; overengineered. Rejected.

### 3. CBZ in v1; CBR deferred; magic-byte sniff to catch mislabeled files

**CBZ (ZIP-of-images)** ships in v1. Java's `java.util.zip` covers it with zero dependencies.

**CBR (RAR-of-images)** is deferred. RAR is proprietary; the pragmatic library is `junrar` under the UnRAR license, which is distributable but requires a legal conversation and adds a ~500KB JAR. Modern comic releases are almost universally CBZ; deferring CBR carries a small user cost and no architectural cost — adding a second `ComicArchive` implementation later is purely additive.

To avoid silent failures on mis-labeled files, **magic-byte sniffing** decides whether a file is a ZIP or a RAR regardless of its extension:

- `PK\x03\x04` → treat as CBZ, works.
- `Rar!\x1A\x07` → not supported in v1; the Detail Screen shows a clean "Comic Book RAR format not yet supported" message; the Read button is absent.

## Scope of the v1 comic reader

The reader clones **CDisplayEx's default** interaction model, deliberately minimal:

- **Paginated only.** No webtoon (vertical continuous) mode, no two-page spread. One image on screen at a time.
- **Fit Whole**, hard-coded. The entire page is visible, letterboxed against the reader background. No preference to change it.
- **Tap zones**: horizontal thirds. Left → previous page. Right → next page. Middle → toggle [Immersive Mode]. No vertical zones.
- **Horizontal swipe** also pages (left = next, right = previous). Vertical swipe does nothing.
- **Pinch-to-zoom + drag-to-pan + double-tap-to-reset**, always live regardless of zone.
- **Bottom page scrubber**: draggable slider with "Page N / M" label, appears/hides with the top bar (tied to Immersive Mode).
- **Volume Keys**: Vol Down = next, Vol Up = previous, honouring the global [Volume Key Navigation] "Invert" preference.
- **LTR only.** No RTL / manga toggle in v1.
- **No Formatting Preferences panel button** — nothing is configurable.

Completed threshold: **`page == pageCount - 1`** (last page reached). Matches the intent implied by "no configuration."

## Cover and page count

**Page count** is discovered as follows:

- **LocalFiles**: computed at Add-to-Library — open the archive, list image entries, sort, count. The count is authoritative and persisted on the library-item row.
- **ABS**: displayed pre-open from ABS's own `numPages` metadata; replaced with the authoritative count after first local open. Progress Sync's fraction-to-page conversion uses whichever value is known.

**Cover art**:

- **LocalFiles**: the first image entry in sorted order is the cover, extracted at Add-to-Library into private storage as a scaled thumbnail.
- **ABS**: the Source-supplied cover URL.

## Amendments to existing ADRs and glossary

- **[ADR 0001]** (Hybrid cache/download storage): applies to comic files unchanged; Cache tier on ABS, no Cache tier on LocalFiles.
- **[ADR 0024]** (Annotations anchor on EPUB CFI): unchanged. Comics carry no annotations in v1 for the same reason PDFs don't.
- **[Supported Formats]**: gains Comic Book Archive.
- **[Readable / Listenable]**: Readable covers EPUB, PDF, and comics.
- **[Formatting Preferences]**: gains a comics section stating no user-configurable preferences in v1.
- **[Progress Sync]**: unchanged in shape; a one-line note on the comic canonical + ABS wire codec.
- **[Library Item Detail Screen]**: shows page count for comics (analogous to duration for audiobooks).

## Deferred, on the roadmap

- **CBR** (real RAR archives).
- **`ComicPositionTranslator`** — fraction-string wire codec for interop with the ABS web reader.
- **Webtoon** (vertical continuous) reading orientation.
- **Two-page spread** in landscape.
- **Right-to-left** reading direction for manga.
- **Comic annotations** — page-anchored Bookmarks first, then page-anchored Highlights. Requires generalising the [Annotation] anchor from EPUB CFI to a discriminated union across formats; its own future ADR alongside PDF annotations.
- **Explicit cover selection** for LocalFiles comics whose first image isn't the true cover.
- **Table of Contents** derived from `ComicInfo.xml` when present.
