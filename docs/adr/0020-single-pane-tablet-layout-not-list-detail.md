# ADR 0020 — Tablet Layout is single-pane, not list-detail

**Status:** Accepted

## Context

Within the Tablet Layout (see [ADR 0019](0019-tablet-layout-only-on-expanded-size-class.md)), the headline structural choice is how the content area is composed. Two coherent patterns exist:

- **Single-pane**: a Permanent Navigation Drawer on the leading edge, with a single content pane taking the rest of the window. Tapping a Library Item still pushes the Library Item Detail Screen onto the back stack, same as on phone.
- **List-detail**: the content pane itself is two-paned (e.g. `ListDetailPaneScaffold` from `androidx.compose.material3.adaptive`). The library grid sits on the left; tapping a cover swaps the right pane to the Library Item Detail Screen instead of pushing a new destination.

Comparable apps — Google Play Books, Kindle, Apple Books — all use list-detail on tablets. It is the pattern users compare against.

## Decision

**Tablet Layout uses a single content pane.** Library Item Detail Screen is reached via the same back-stack push as on phone. The Permanent Navigation Drawer is the only structural change to overall page composition; everything past the drawer is the existing phone navigation model with widened content.

## Alternatives considered

**List-detail two-pane content area (`ListDetailPaneScaffold` or equivalent).** Rejected for this scope. The pattern is genuinely "more native" on a tablet, but it is a real navigation model change, not a layout change:

- The back stack behaves differently on tablets: a back press from the detail pane clears the right pane to an empty state, not pops a destination.
- Every screen that currently pushes Library Item Detail (library grid tabs, Series detail, Collections detail, Downloads Screen, future search results) needs to know whether it is pushing or swapping.
- Deep links must resolve into two panes instead of one.
- An empty-detail placeholder state ("Pick a book") becomes a new UX surface to design.
- Existing instrumentation tests that assert "tapping a cover navigates to Library Item Detail Screen" no longer hold uniformly across form factors.

These costs are real but the architecture is also strictly additive: starting with single-pane does not block migrating to list-detail later. The reverse is not true — landing list-detail in the same change as the rest of the tablet work bundles a navigation refactor into a layout PR and makes both harder to review.

## Consequences

- **Tablet Library Item Detail Screen still pushes a destination.** It just renders in the widened single content pane. Back-arrow behaviour is identical to phone.
- **The Library Item Detail Screen itself is two-column** (cover/actions left, metadata/description right with independent scrolling) — but this is layout within a single destination, not a two-pane navigation model.
- **Migration to list-detail later is an explicit non-decision.** If the experience starts losing to comparable apps on tablet, list-detail is the natural next move and should get its own ADR. The current decision is "not yet," not "never."
- **Tests do not need a per-form-factor navigation model.** A test that asserts `LibraryItemDetailScreen` is on the back stack after tapping a cover holds on both phone and tablet.
