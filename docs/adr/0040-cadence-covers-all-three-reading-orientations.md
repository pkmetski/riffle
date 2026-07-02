# ADR 0040 — Cadence covers all three reading orientations

**Status:** Proposed

## Context

[Auto-Scroll] (see [ADR 0037](0037-auto-scroll-session-mode.md)) is deliberately unavailable in **paginated** mode: it creeps the reading surface upward at pixels-per-second, and paginated has no scroll axis to creep along. The load-bearing behaviour that constrains Auto-Scroll to Vertical/Continuous is its second decision — at the bottom of a Vertical chapter it **stops** rather than auto-advancing, because at `scrollY == max` the user's eye is mid-viewport and an immediate `goForward()` would swap out ~half a viewport of unread text.

[Cadence] is a peer hands-free mode with a different unit of advance: the highlight moves sentence-by-sentence at `wordCount(sentence) / wpm`. The mechanical constraint that keeps Auto-Scroll off paginated does not apply — the "next sentence" advance is orientation-agnostic. In paginated the advance is either a highlight change within the current page or a `goForward` page turn once the next sentence sits past the visible column. In Vertical/Continuous it's a highlight change plus a small scroll to keep the sentence in the viewport.

A future reader looking at Auto-Scroll's paginated ban will reasonably ask: *if paginated has no scroll axis and Auto-Scroll therefore doesn't run there, why does Cadence run there?* The two features look symmetric — both hands-free, both driven by a WPM knob, both mutually-exclusive with Readaloud — and the divergent orientation coverage is exactly the kind of asymmetry that reads as an oversight without an explicit record.

## Decision

Cadence is available in **all three** reading orientations: paginated, Vertical, and Continuous. At the end of a chapter Cadence **auto-advances** in all three orientations rather than stopping.

The rationale differs from Auto-Scroll's on two axes:

- **What advances.** Auto-Scroll advances pixels; Cadence advances the highlighted sentence. Sentence advance has a natural mapping in every orientation: within the visible surface it's a highlight change; across the visible surface's edge it's a `goForward` (page turn in paginated; scroll-and-load in Vertical; native scroll past the boundary in Continuous).
- **What "read to the end" means.** Auto-Scroll's "user has read the bottom half-viewport" is only inferable from time-in-viewport, and the inference is weak — hence its Vertical-end stop. Cadence's dwell-on-last-sentence *is* the read-through signal: by the time the last sentence's dwell expires, the user has demonstrably consumed it. Auto-advancing across chapter boundaries follows.

## Consequences

- Users get a single hands-free feature that behaves the same regardless of orientation. Paginated readers — a distinct camp — are no longer excluded from a hands-free mode.
- The Auto-Scroll orientation ban stays intact; this ADR does not reopen ADR 0037's Vertical-end stop decision, which remains correct for velocity-driven scroll.
- Cadence's paginated implementation reuses Readium's existing `goForward` and `currentLocator` mechanics — no new page-turn primitive. The paginated path is arguably the simplest of the three, because "sentence advance leaves the current page" becomes a normal page turn instead of a scroll fight.
- Cadence and Readaloud remain **mutually exclusive at runtime** (see the [Cadence] glossary entry): starting one auto-stops the other, symmetrically. That rule is orientation-independent and unaffected by this decision.
- The glossary asymmetry — Auto-Scroll gated to Vertical/Continuous, Cadence available everywhere — is documented in both terms' entries and cross-referenced by this ADR.

## Alternatives considered

- **Mirror Auto-Scroll: gate Cadence to Vertical/Continuous.** Preserves surface-level symmetry between the two hands-free modes. Rejected: it forfeits Cadence's clearest use case (paginated readers, where the highlight+page-turn shape is arguably cleaner than in Vertical) without a mechanical reason, and it perpetuates the "hands-free is for scroll users" framing that has no user-facing justification.
- **Cover paginated and Continuous only; skip Vertical.** Vertical is the one orientation where Cadence's chapter-boundary handling diverges from Auto-Scroll's (auto-advance vs. stop-and-wait) — one could argue "avoid the divergence, drop Vertical." Rejected: Vertical is Riffle's second-most-used scroll mode and its chapter-boundary rule falls out cleanly from the dwell-on-last-sentence signal — there is no real ambiguity to duck.
- **Make chapter-boundary behaviour a Formatting Preference (`cadence.chapterBoundary = advance | pause`).** Configurable satisfies both camps. Rejected: adds a knob for a decision the user is unlikely to have a considered opinion about, and the pause case is a cheap follow-up (dwell multiplier on the last sentence of a chapter) that does not require its own toggle.
