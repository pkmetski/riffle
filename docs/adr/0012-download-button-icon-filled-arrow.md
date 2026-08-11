# ADR 0012 — Download Button uses filled arrow, not checkmark

**Status:** Accepted

## Context

The Library Item Detail Screen needs a single icon-only button to represent the download action states from ADR 0001: not downloaded (tap to download), cached but not downloaded (tap to promote an existing Cache to a Download), in progress (spinner), and downloaded (tap to remove). The natural "done" glyph in Material Design is a checkmark (`done` / `check`).

However, a planned future feature — "Mark as read / Mark as unread" — will also appear on the Library Item Detail Screen and will use a checkmark as its primary visual indicator. Using a checkmark for the downloaded state would create two checkmark-bearing controls on the same screen with unrelated semantics, causing confusion.

## Decision

Use a **filled arrow** (the same downward-arrow glyph as the "not downloaded" state, rendered filled and in the primary colour) to indicate the downloaded state. The button states are:

- **Outline arrow** — not downloaded; tap initiates download
- **Secondary filled arrow** — cached but not downloaded; tap promotes the Cache to a Download
- **Spinner** — download in progress; Read button dims
- **Primary filled arrow** — downloaded; tap removes the download

The icon shape stays constant across the action states; fill carries local availability, and colour distinguishes cached-only local availability from a permanent user-requested download. Removing a download also removes any matching same-item Cache (EPUB/PDF/CBZ file cache or audiobook cache directory), so the detail control does not fall back from Downloaded to Cached after removal.

## Alternatives considered

- **Checkmark for downloaded state** — intuitive "task complete" metaphor, but conflicts with the future "Mark as read" button on the same screen.
- **Two-tap removal with trash icon on second tap** — adds a transitional state that increases tap count without providing meaningful safety (the Undo snackbar is the safety net instead).
- **Text label beneath the button** — ruled out; the screen is intended to be clean and label-free in that area.

## Consequences

- The filled-arrow convention is non-standard and must be consistent wherever download state is shown (detail screen and Downloads Screen indicator icons).
- The "Mark as read" feature must not use a filled arrow for its own state indicator.
- Removing a single download is instant with no Undo on the detail screen. Bulk removal from the Downloads Screen uses a confirmation dialog (cost of mistake is higher).
