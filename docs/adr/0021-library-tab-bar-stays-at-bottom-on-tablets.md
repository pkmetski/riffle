# ADR 0021 — Library Tab Bar stays at the bottom in the Tablet Layout

**Status:** Accepted

## Context

The Library Tab Bar is the four-icon bar (Home / Series / Collections / All Books) pinned to the bottom of every Library screen on phones. In the Tablet Layout, the Navigation Drawer becomes a Permanent Navigation Drawer pinned to the leading edge. This puts navigation chrome on two adjacent edges of the same screen.

Material 3's guidance prefers moving secondary view-switching tabs to the top of the content area on tablets, reserving the bottom edge for nothing (or, in pure phone designs, for top-level destinations). The rationale combines two ideas:

1. **Semantic separation.** `NavigationBar` represents top-level app destinations; `TabRow` represents secondary view-switching within a destination. On phones, a bottom bar conflates the two because there is no room for both. On tablets with a permanent drawer, the conflation can be cleanly resolved by promoting the tabs to a top `TabRow`.
2. **Visual weight.** Chrome on two adjacent edges frames the content awkwardly.

The ergonomic argument (thumb reach) is weaker on tablets, where the bottom edge is not appreciably closer to the user's hands than the top, and is sometimes further (tablet held in landscape with thumbs near the top corners).

## Decision

**The Library Tab Bar remains pinned to the bottom in the Tablet Layout, unchanged from the phone layout.** No `TabRow` is introduced at the top of the content pane, and the tabs are not promoted into the Permanent Navigation Drawer.

## Alternatives considered

**Move the four tabs to a `TabRow` at the top of the content pane.** This is the Material-canonical choice and the closest to "feels like a tablet app." Rejected because the semantic argument it relies on — that Riffle's Library Tab Bar is *really* secondary tabs masquerading as primary navigation — is real but not compelling enough to justify moving a UI element users have already learned. The phone-to-tablet transition should feel like the same app, not a re-skin.

**Promote the four tabs into the Permanent Navigation Drawer as a sub-tree under the active Library.** Rejected: the drawer is the "cross-Library" surface (switch Library, Downloads, Settings); the tab bar is the "intra-Library" surface (which view of this Library). Folding the latter into the former collapses that separation and makes the drawer's contents change shape every time the user switches Library, which is jarring.

## Consequences

- **Navigation chrome appears on two adjacent edges in the Tablet Layout** (drawer leading, tab bar bottom). This is a knowing deviation from Material guidance and is the most likely thing a reviewer or new contributor will flag as a bug. Linking this ADR from the relevant code is appropriate.
- **The four tabs use `NavigationBar` (not `TabRow`) on both phone and tablet.** Implementation stays uniform; only the surrounding shell differs by size class.
- **Reversal cost is low.** If the bottom-bar-on-tablet choice ages badly, replacing the `NavigationBar` with a top `TabRow` in the Expanded branch is a localised change that does not touch the tabs' contents, their ViewModels, or their tests. This decision is recorded primarily to short-circuit the recurring "this looks wrong on tablet" conversation, not because the choice is hard to undo.
