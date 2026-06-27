# ADR 0037 — Auto-Scroll: a clock-driven session mode that suspends Readaloud and overrides volume-nav + wake-lock preferences

**Status:** Proposed

## Context

Riffle's reader supports three orientations today: **Horizontal** (paginated), **Vertical** (Readium scroll, one chapter at a time), and **Continuous** (custom `ContinuousReaderView` stacking chapter WebViews — see [ADR 0032](0032-continuous-scroll-mode.md)). In the two scroll-axis orientations the user advances the page by hand — a thumb drag, a fling, or hardware volume buttons via [Volume Key Navigation].

We want a **hands-free reading mode**: the page creeps upward on its own at a user-set pace, so the reader never needs to touch the screen. Common in Moon+ Reader, KOReader, and Kindle's beta auto-scroll feature.

This is **not** the same as the existing Readaloud follow-along. Readaloud also moves the page on its own, but its pace is **dictated by audio** via the Media Overlay SMIL; auto-scroll is **dictated by a wall clock**. The two share the goal "keep the page moving so the reader doesn't have to," but they answer "moving at what rate?" differently. Both are forms of scroll authority, and *they cannot both be the active authority at the same time*.

Several existing preferences also become awkward under auto-scroll:

- **[Volume Key Navigation]** (page-turn by Volume Down / Up) — the page-turn meaning is null while auto-scroll runs (auto-scroll *is* the page turn).
- **[Screen Wake Lock]** — a user who turned it off would see the screen sleep ~30 s into a hands-free session, visibly breaking the feature.
- **[Immersive Mode]** — the chrome that hosts the toggle disappears once the user taps content, so the speed control needs a host that survives Immersive Mode.

The decisions in this ADR are the surprising ones — the load-bearing trade-offs a future reader will ask about. Tactical choices (WPM as the speed unit, the pill's pixel placement, the anchor-on-reflow resume mechanism, the auto-advance-with-brief-pause at Vertical chapter boundaries) are not load-bearing and live only in the implementation.

## Decision

Add **Auto-Scroll** as a session-level reader mode, available only when [Formatting Preferences].`orientation ∈ {Vertical, Continuous}`. The mode is entered and exited from a top-bar toggle; while active, an in-content HUD pill (small, translucent, bottom-right) shows the current speed and exposes finer adjustment. Speed is a per-book [Formatting Preferences] value (WPM, with a global default), persisting like font size and line spacing.

Three behaviours are load-bearing:

**1. Mutual exclusion with Readaloud.**
Starting Readaloud audio stops a running Auto-Scroll; starting Auto-Scroll stops a running Readaloud. The transition is silent — no dialog, no warning. Readaloud's text-following highlight is unchanged when it is the active scroll authority; Auto-Scroll has no highlight concept. The two are never simultaneously active.

**2. Volume keys are repurposed while Auto-Scroll runs.**
The [Volume Key Navigation] global preference, which normally maps Volume Down → next page and Volume Up → previous page, is temporarily reinterpreted while Auto-Scroll is active: Volume Down → slower, Volume Up → faster. The Invert Volume Keys sub-preference still applies (it swaps the speed-nudge direction the same way it swaps the page-turn direction). The page-turn mapping returns the instant Auto-Scroll stops — no state lag, no opt-in.

**3. The [Screen Wake Lock] is forced on while Auto-Scroll runs.**
Regardless of the user's global Wake Lock preference, a wake lock is held for the duration of the Auto-Scroll session and released the moment it stops. The global preference is not mutated; it resumes governing the reader the moment Auto-Scroll exits.

## Consequences

- Users get a single conceptual model — *one scroll authority at a time* — that covers both audio-paced and clock-paced motion. A book that has Readaloud audio cued does not silently overlay two competing pace sources.
- The Volume Key Navigation and Screen Wake Lock glossary entries cross-reference [Auto-Scroll] to document the override. Engineers reading either preference's code path need to know its semantics are conditionally suspended.
- Auto-Scroll inherits Riffle's existing per-book Formatting Preferences plumbing for speed persistence — no new storage layer.
- A user paused at the end of a chapter in Vertical mode no longer has to pull 160 dp to advance: Auto-Scroll calls `goForward()` automatically on reaching the bottom (briefly pausing to absorb Readium's transition flash). This is a tactical implementation detail, not a load-bearing decision; it's recorded here only to head off the inevitable "why doesn't Vertical mode stop at chapter end?" question.

## Alternatives considered

- **Let Auto-Scroll and Readaloud coexist.** Two scroll sources, two paces. Either they happen to match (Auto-Scroll is then doing nothing useful) or they disagree (one is wrong; the highlight or the page is fighting the user). Rejected as incoherent for the user model.
- **Readaloud silently wins; Auto-Scroll yields to it without stopping.** Cleaner for keyboard-shortcut users who toggle audio on and expect it to take over. Rejected because the speed pill staying visible while Auto-Scroll is "paused" is misleading state, and users who later pause Readaloud would expect Auto-Scroll to silently resume — a behaviour that's both surprising (resumes itself on an unrelated user action) and easy to forget about.
- **Add a touch-only speed control (no volume-key repurpose).** Keeps Volume Key Navigation's semantics constant in all contexts. Rejected: a touch-only control on a *hands-free* feature defeats the gesture. Volume keys are the most ergonomic hands-off adjustment available, and their existing meaning is null during Auto-Scroll anyway, so the repurpose costs nothing real.
- **Respect the user's Wake Lock preference; let the screen sleep.** Principled. Rejected: Auto-Scroll is a strictly-broken feature when the screen sleeps after 30 s. Forcing wake lock on for the session is a narrow, scoped override that respects the global preference everywhere else.
- **Show a confirmation dialog when starting Auto-Scroll with Wake Lock off** ("Auto-Scroll works best with the screen kept on. Enable now?"). Educational but adds friction to a deliberately one-tap gesture. The silent override gets the user what they asked for; the global preference is untouched.
- **Put Auto-Scroll on/off in [Formatting Preferences] instead of the top bar.** Misclassifies a session-level mode as a persistent preference. The top-bar toggle matches the pattern Riffle already uses for [Book Search] (session-level mode entered from the top bar; speed/options configurable in Formatting).
- **Build for PDF in v1.** Riffle has no PDF continuous mode today (despite an older CONTEXT.md line that suggested otherwise — corrected in this change). Auto-Scroll for PDF requires PDF continuous mode first; deferred until that mode itself is on the roadmap.
