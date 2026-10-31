---
name: annotations-text-graph-symmetric
description: "Graph inherits annotation ONLY when text before AND text after were captured in one single selection. Separately-created highlights either side of a figure do NOT auto-merge and do NOT auto-annotate the figure between them."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: fe767d67-6857-4b37-960f-593e23c66aff
---

Reversed 2026-07-10 (superseding the 2026-07-09 "text-graph symmetric" position).

**Rule:** a figure only becomes part of a highlight when the user's SINGLE selection covers text on BOTH sides of the figure at creation time. Two same-colour highlights that happen to sit either side of a figure — created as separate selections — must stay separate and must NOT pull the figure in as an `embeddedFigure`. Two separate creations = two separate intents.

**Why:** on 2026-07-10 the user pushed back on the earlier symmetric model: "if the text before the graph is annotated, and the text after it is also, this creates 2 separate annotations. however, then the graph is also auto-annotated. this is wrong — the graph must only inherit annotation if both the text before and after are annotated TOGETHER. with separate annotations, it doesn't mean the user chose the whole area that includes the graph." The prior fix (`figureGuard` reversal, [[annotations-text-graph-symmetric]] as it was) was the mirror-image bug — the 2026-07-10 change is the correct final position.

**How to apply:**
- `isMergeEligible` accepts ONLY TYPE_HIGHLIGHT candidates. TYPE_IMAGE annotations (long-press-created) are never auto-merged into anything.
- `findAnyMergeableNeighbor` runs `hasFigureInGap` after a text-adjacency match succeeds — if any `<img>` / `<svg>` / `<picture>` / `<figure>` sits in the DOM char-range between the two highlights (widened by 1 char to catch void figures at the exact boundary), the merge is rejected.
- `mergeAdjacentIntoHighlight` no longer calls `findAbsorbableImageAnnotations` (removed) or `absorbFigureIntoAdjacentHighlight` (removed). Dismissing a standalone TYPE_IMAGE popup does nothing merge-related.
- `buildMergedEmbeddedFigures` walks the merged range but only carries figures that were already inside the source highlights (the gap-check guarantees the merged range can't newly enclose a figure).
- Create-time capture is untouched: `createHighlight`'s `findEnclosedFiguresInHtml` walk still detects figures inside a single-selection range → they become `embeddedFigures`. This is the ONLY path that annotates a figure via a text highlight.

**Related memories:**
[[db-migration-version-collision]] — unrelated.
