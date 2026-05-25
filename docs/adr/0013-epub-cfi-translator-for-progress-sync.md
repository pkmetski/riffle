# ADR 0013 — Character-count CFI translator for EPUB progress sync (both directions)

**Status:** Accepted

## Context

EPUB Progress Sync (see ADR 0008) exchanges positions with the ABS server as two fields:

- `ebookLocation` — an EPUB CFI string pinpointing the exact reading position.
- `ebookProgress` — a book-wide float in [0, 1] used by ABS for display and statistics.

ABS's web reader (epub.js) generates CFIs by counting raw text characters in the chapter's DOM. Readium (Riffle's renderer) generates CFIs using XPath-style node addresses rooted in the publication's spine structure. The two formats are structurally incompatible: a Readium CFI sent to ABS is opaque to epub.js, and an epub.js CFI stored on the server cannot be navigated to by Readium directly.

This means naive pass-through of Readium CFIs breaks cross-client position sync: a position saved from Riffle is unreadable by the web reader, and a position saved from the web reader cannot be used to restore position in Riffle.

## Decision

All EPUB `ebookLocation` values crossing the Riffle↔ABS boundary — in **both** directions — are translated through `EpubCfiTranslator` using character-count-based CFI positions over the chapter's raw HTML text content.

**Outbound (Riffle → ABS):**
1. Use `locations.progression` (Readium within-chapter progression, 0–1) from the current `Locator`.
2. Call `progressionToCfiDocPath(progression, chapterHtml)` to convert to a CFI document path anchored to a specific text node and character offset.
3. Prepend the spine step to form a full `epubcfi(/6/N!<docPath>)` string sent as `ebookLocation`.

**Inbound (ABS → Riffle):**
1. Extract the document path from the server CFI with `extractCfiDocPath`.
2. Call `cfiDocPathToProgression(docPath, chapterHtml)` to recover the within-chapter progression (using ID-anchored navigation first, numeric fallback second).
3. Combine spine index + within-chapter progression into a Readium `Locator` to restore the reader position.

`ebookProgress` (the book-wide float) is **not** translated — it is a separate display/statistics field passed directly. It is not used for navigation in the primary CFI path. It is used as a fallback in `serverProgressToLocator` only when `ebookLocation` is empty or unparseable (e.g. 404 / first-open case where the server has no CFI record yet).

## Invariant

> Every `ebookLocation` sent to or received from the ABS API must pass through `EpubCfiTranslator`. No raw Readium CFIs or raw epub.js CFIs cross the API boundary untranslated.

## Alternatives considered

**Pass Readium CFIs directly:** Simple but produces CFIs incompatible with epub.js. Cross-client position sync fails silently.

**Use `ebookProgress` (float) for inbound navigation:** `pub.locateProgression(progress)` maps book-wide progress to a Locator. No chapter HTML is needed. Rejected because it is chapter-level at best — the translator recovers paragraph-level precision from the CFI's character offset, which is the same fidelity epub.js writes.

**Use Readium's CFI parser for inbound:** Readium can parse its own CFIs but not epub.js's character-offset CFIs. Rejected for the same incompatibility reason.

## Consequences

- `EpubCfiTranslator` functions (`progressionToCfiDocPath`, `cfiDocPathToProgression`, `extractCfiDocPath`) are the single translation point for all EPUB position data exchanged with ABS.
- Chapter HTML is required for both directions. The ViewModel caches it by spine index (`chapterHtmlCache`) to avoid repeated zip reads.
- Round-trips are self-consistent: a position saved from Riffle and immediately read back produces a within-chapter progression within a small margin of the original (character approximation; tested in `EpubCfiTranslatorTest`).
- `serverProgressToLocator` must use the CFI + translator path as the primary route. `pub.locateProgression(ebookProgress)` is permitted only as a last-resort fallback when `ebookLocation` is empty or fails to parse (the 404 / no-progress case).
- PDF sync is unaffected — PDFs have no chapters and use `locations.progression` directly as `ebookProgress`.
