# Highlight auto-merge — design

## Problem

Readers often highlight adjacent text in two operations: they highlight one phrase, then extend by highlighting the next phrase. The result is two separate `AnnotationEntity` rows that visually look like one continuous highlight but behave like two — separate delete taps, separate colors on recolor, separate rows in the annotations panel.

We want adjacent highlights to auto-merge into a single annotation when it's safe to do so.

## Scope

- **In scope (post-rollout amendment 2026-07-05):** merge at *recolor* and *note-clear* only. Create-time merge was removed after user testing — it silently absorbed brand-new selections into an adjacent same-colour neighbour, blocking the user from differentiating the new highlight (recolour, add note) before the merge fired. Merge is now a purely opt-in-by-edit operation.
- **Out of scope:** bulk migration of existing highlights. Existing separate-but-adjacent highlights stay as-is unless the user re-touches one of them.
- **Out of scope:** merging across chapter boundaries (different `spineIndex`).
- **Out of scope:** any UI to disable auto-merge. Merge is unconditional when the eligibility rules pass.

## Eligibility rules

Two highlights A and B merge only if **all** of these hold:

1. Same `spineIndex` (same chapter).
2. Same `color`.
3. Both `note` fields are null or empty.
4. A and B are **text-adjacent** (see below).

If any of these fails, the highlights stay separate. In particular:

- Different colors → separate (semantic distinction: yellow-important vs blue-question).
- Either side has a note → separate (merging would drop or awkwardly concatenate notes).
- Any gap that isn't a single whitespace run → separate (user intentionally skipped text).

## Text-adjacency

Reuses the text-matching pattern already used by `highlightOverlapsAtSamePosition` at `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt:1501`.

A is adjacent to B (in that order in the document) when:

- `A.textAfter` starts with `B.textSnippet`, **or**
- equivalently, `B.textBefore` ends with `A.textSnippet`.

A single run of whitespace between the two snippets is collapsed for the comparison — so "end of one sentence." + " " + "Start of next" merges naturally. Any non-whitespace character between them means "not adjacent."

**Judgment call flagged for review:** whitespace-collapse rule. Alternative is strict abutment (no gap at all). Default here is collapse-one-whitespace-run because sentence-boundary highlights are the most common merge case.

## When merge fires (post-rollout amendment 2026-07-05)

**Only on highlight-actions popup dismiss** (`AnnotationSession.dismissHighlightActions`). The popup close is the commit point: the user has finished iterating on colour and note. On dismiss we read the row's *final* colour + note and run the adjacency scan; if a same-chapter same-colour no-note neighbour matches, absorb it.

Firing on the individual recolour / note edits (as this doc initially proposed) surprised users. While they were still iterating in the popup — trying a colour, adding then removing a note — the first same-colour recolour or note-clear would silently absorb a neighbour before they had a chance to differentiate it. Deferring the check to popup close makes the merge feel intentional.

**Not triggered on:** create (see Scope), delete (only removes rows), popup open (state hasn't been committed).

## Merge action

Given surviving highlight S (the row being edited) and neighbour N to be absorbed:

1. Compute merged text fields:
   - `textBefore` = leftmost highlight's `textBefore`.
   - `textAfter` = rightmost highlight's `textAfter`.
   - `progression` = leftmost highlight's `progression`.
2. **DOM-derive the merged `textSnippet`** (post-rollout amendment): extract the exact readable-text substring from the chapter DOM covering `[startChar, startChar + composedLength)`. Naïvely concatenating the two source snippets with a captured whitespace run drifts from the DOM whenever Readium's whitespace capture differs between `text.highlight` and `text.after`/`text.before` (NBSP vs space, newline vs space), which makes Readium's decorator fall back to a partial-range placement (visible as "first part missing").
3. **Safety check:** if the DOM-derived snippet doesn't agree with the composed snippet under whitespace-normalised, case-insensitive comparison, abort the merge. This catches false-positive adjacency matches (e.g. tail-of-context coincidence) before they commit.
4. Rebuild a spanning CFI range covering both via `buildHighlightCfiRangeForSelection` on the DOM-derived snippet.
5. **Defer neighbour deletes until all computations succeed** — no orphaned deletes if CFI build fails.
6. Delete each absorbed neighbour + the anchor row; upsert the merged row.
7. Schedule sync (`scheduleAnnotationSync()`) once at the end — one debounce for the whole merge.

## Chain merge

After a merge, the surviving highlight may now be adjacent to another neighbor (e.g. user highlights the middle of three touching phrases last, joining left and right in one go, or recolor unifies three-in-a-row). Loop the adjacency scan on the surviving row until no more neighbors match. Bounded by "number of same-chapter same-color no-note highlights" — cheap, and in practice ≤ a few iterations.

## Reader-mode considerations

All three reader modes (paginated, vertical, continuous) share a single highlight creation path via `EpubReaderViewModel.createHighlight`. The merge logic lives there and applies uniformly.

Continuous mode renders highlights via DOM `data-riffle-ann` marks (`AnnotationSession.applyAnnotationHighlights`); a deleted-then-upserted row will re-render on the next annotation-state emission, same as any recolor or note edit today. No mode-specific code needed.

## Sync

Merge = one delete + one upsert on the same chapter. Both flow through the existing `annotationStore` + `scheduleAnnotationSync()` path, so the sync layer treats it as two independent operations. Devices without this feature receiving a merged annotation see the merged row appear and the neighbor row tombstone — normal behavior, no protocol change.

## Testing

Regression tests must cover the eligibility matrix and the merge action. All at the JVM level — the merge decision is pure logic over `AnnotationEntity` fields plus text-adjacency over strings.

Extract the eligibility + adjacency + merged-field computation into a top-level `internal` helper (e.g. `HighlightMerge.kt`) so it's unit-testable without a ViewModel harness.

Assertions that would flip red if the fix were reverted:

- **Merge happens:** same color, both notes empty, snippets abutting → after create, exactly one row exists with the combined snippet.
- **Different colors don't merge:** yellow next to blue → two rows survive.
- **Note blocks merge:** left has a note, right doesn't → two rows survive; ditto right-has-note.
- **Gap blocks merge:** two characters between snippets → two rows survive.
- **Whitespace collapses:** exactly one space between snippets → one row after create.
- **Recolor triggers merge:** two adjacent different-color no-note rows; recolor one to match → one row after recolor.
- **Note-clear triggers merge:** two adjacent same-color rows where one has a note; clear the note → one row after the update.
- **Note edit (still non-empty) does not trigger merge:** same setup; edit the note text without clearing it → still two rows.
- **Chain merge:** three adjacent same-color no-note rows created in outer-then-middle order → one row after the middle create.
- **textAfter/textBefore preserved:** merged row's `textBefore` = leftmost's, `textAfter` = rightmost's.
- **Sync scheduled once:** exactly one `scheduleAnnotationSync` call per merge.

Existing highlights are untouched (out of scope) — no test needed for "old adjacent rows stay separate on app launch" beyond the standing invariant that nothing scans the DB at launch.

## Migration

None. Schema unchanged. Existing rows untouched.
