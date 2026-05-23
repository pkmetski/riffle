# ADR 0012 — Download Button uses filled arrow, not checkmark

**Status:** Accepted

## Context

The Library Item Detail Screen needs a single icon-only button to represent three download states: not downloaded (tap to download), in progress (spinner), and downloaded (tap to remove). The natural "done" glyph in Material Design is a checkmark (`done` / `check`).

However, a planned future feature — "Mark as read / Mark as unread" — will also appear on the Library Item Detail Screen and will use a checkmark as its primary visual indicator. Using a checkmark for the downloaded state would create two checkmark-bearing controls on the same screen with unrelated semantics, causing confusion.

## Decision

Use a **filled arrow** (the same downward-arrow glyph as the "not downloaded" state, rendered filled and in the primary colour) to indicate the downloaded state. The three states are:

- **Outline arrow** — not downloaded; tap initiates download
- **Spinner** — download in progress; Read button dims
- **Filled arrow** — downloaded; tap removes the download (with Undo snackbar)

The icon shape stays constant across all states; fill and colour carry the state meaning.

## Alternatives considered

- **Checkmark for downloaded state** — intuitive "task complete" metaphor, but conflicts with the future "Mark as read" button on the same screen.
- **Two-tap removal with trash icon on second tap** — adds a transitional state that increases tap count without providing meaningful safety (the Undo snackbar is the safety net instead).
- **Text label beneath the button** — ruled out; the screen is intended to be clean and label-free in that area.

## Consequences

- The filled-arrow convention is non-standard and must be consistent wherever download state is shown (detail screen and Downloads Screen indicator icons).
- The "Mark as read" feature must not use a filled arrow for its own state indicator.
- Removing a single download is instant with a timed Undo snackbar. Bulk removal from the Downloads Screen uses a confirmation dialog (cost of mistake is higher).
