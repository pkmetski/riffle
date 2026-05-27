# ADR 0015 — Book Search via TopAppBar in-place transformation

**Status:** Accepted

## Context

The EPUB reader needs an in-book text search. The reader's `TopAppBar` already hosts three action icons (Search, TOC, Formatting). Two UI patterns were viable for exposing the search input:

1. **In-place TopAppBar transformation** — tapping the search icon collapses the title and other icons and expands a search field inline within the bar. A ✕ button dismisses search and restores the normal bar.
2. **Slide-in panel** — a dedicated search panel slides in from the side or bottom, consistent with how the TOC and Formatting panels work.

## Decision

Use the in-place TopAppBar transformation (option 1).

## Alternatives considered

**Slide-in panel** — consistent with the existing TOC and Formatting panels. Rejected because search is a transient query interaction: the user types, jumps to a result, and dismisses. It is not a browsing or configuration surface that warrants persistent screen real estate. Adding a third panel type would also increase the complexity of panel state management (only one panel open at a time, mutual exclusion logic, etc.) for no UX gain.

## Consequences

- The search icon is positioned as the leftmost of the three TopAppBar action icons (Search → TOC → Formatting), placing the most action-oriented control closest to the title.
- The back arrow always exits the reader regardless of search state; it is never repurposed as a search-dismiss control.
- Opening search exits Immersive Mode (revealing the TopAppBar) consistently with all other TopAppBar interactions.
- The PDF reader omits the search icon entirely — Readium's `SearchService` is EPUB-only and the Pdfium adapter has no equivalent.
- When future bookmark support is added, the bookmark icon will be appended to the right of Formatting, keeping Search as the leftmost action icon.
