# ADR 0039 — Sentence-highlight pipeline generalized for Cadence alongside Readaloud

**Status:** Proposed

## Context

[Readaloud] renders a moving, sentence-level highlight over the ABS EPUB while its aligned audio plays. Today that pipeline lives entirely in `ReadaloudController` (~309 lines) and its collaborators: sentences are sourced from the [Readaloud Sidecar] (SMIL + aligned chapter text), the "current sentence" over time is driven by the audio clock via the SMIL clip map, the highlight itself is rendered by the shared [HighlightRenderer] (`applyReadaloud(fragmentRef, quotes, color)` in both `ReadiumHighlightRenderer` and `ContinuousHighlightRenderer`), and an auto-follow controller keeps the highlighted sentence in the viewport as the WebView reflows.

We are adding [Cadence], a hands-free reading mode that shows the same moving sentence highlight without audio: sentences are tokenized from the live ABS EPUB DOM at chapter-load time, and the "current sentence" advances on a WPM-driven wall clock (`wordCount(sentence) / wpm`). Cadence and Readaloud share **highlight rendering, viewport-follow, and — on Vertical/Continuous — the same auto-advance-through-chapters mechanic**; they diverge on **sentence source, timing source, and (for Readaloud only) audio playback, audio-domain chapter navigation, and progress sync**.

A naive implementation would fork the pipeline: leave `ReadaloudController` alone, add a parallel `CadenceController` that re-implements sentence identity, DOM span injection, viewport-follow, and the highlight-driver plumbing against the same `HighlightRenderer`. That path is what a future reader will *expect* to see if this ADR does not exist.

## Decision

Extract the shared pipeline into a **`SentencePlaybackController`** owning highlight-driving, DOM span injection, and viewport-follow, backed by two collaborators the callers plug in:

- **`SentenceSource`** — produces `Map<FragmentRef, SentenceQuote>` for a chapter and takes responsibility for making those `FragmentRef`s (`"href#spanId"`) resolvable in the WebView. Readaloud's implementation is `SidecarSentenceSource` (Sidecar SMIL + aligned text via the cross-EPUB index). Cadence's implementation is `DomSentenceSource` (walks the chapter DOM, runs the WebView's `Intl.Segmenter` against the EPUB's `xml:lang`, wraps each sentence in a synthetic `<span id="cd-N">`).
- **`SentenceTicker`** — exposes `currentFragment: StateFlow<FragmentRef?>` and `play() / pause() / stop()`. Readaloud's implementation is `AudioClockTicker` (ExoPlayer position → SMIL clip map → fragment). Cadence's implementation is `WpmTicker` (per-sentence `wordCount / wpm` timer).

Fragment identity stays `"href#spanId"` — the existing `HighlightRenderer` contract is preserved, only the method name changes (`applyReadaloud` → `applySentenceHighlight`).

**Not moved into the shared controller** (stay in `ReadaloudController`):
- Audio playback (ExoPlayer, audio focus, media session).
- Audio-domain chapter navigation (prev/next-chapter as audio seek; see `reference_readaloud_chapter_nav_uses_reader_toc.md`).
- ABS audiobook streaming, bundle fallback.
- Two-peer [Progress Sync] with the ABS audiobook remote.

Cadence's chapter navigation is text-domain — the ticker's `next` past the last fragment of a chapter calls the shared controller's `goForward`, which auto-advances at the boundary (see ADR 0040).

**Sequencing.** The extraction ships as a **prerequisite, behaviour-preserving refactor PR**: Readaloud is migrated onto the new interfaces, all existing tests pass with no user-visible change, and only then does Cadence land as a second implementation set + a top-bar toggle + Settings surface.

## Consequences

- One code path renders and follows the moving sentence highlight regardless of whether the tick comes from audio or a clock. Bugs in viewport-follow, chapter-turn timing, or renderer swap-reapply are fixed once for both features.
- Readaloud remains the owner of everything audio — audio playback, audio-led [Progress Sync], and audio-domain chapter navigation continue to live in `ReadaloudController`. Cadence has no audio surface.
- Adding a third sentence-driver later (imagine an eye-tracker producing "user is here"), or a third source (server-supplied alignment for non-matched books), is a `SentenceSource` / `SentenceTicker` implementation — no controller work.
- `HighlightRenderer.applyReadaloud` is renamed `applySentenceHighlight`; call sites update mechanically. The rename is part of the prerequisite refactor PR, so Cadence's PR is a small, additive change on top.
- The seam is opinionated about ownership: DOM span injection is `SentenceSource`'s job, viewport-follow is the controller's job. Alternative shapes that push injection into the controller, or turn `SentenceTicker` into a generic clock the controller pipes through a mapper, were considered and rejected below.

## Alternatives considered

- **Duplicate the pipeline into a parallel `CadenceController`.** Zero refactor risk on Readaloud, but doubles the surface area for bugs like the historic annotation-focus reflow race, readaloud-chapter-flash, and renderer-swap-reapply — each of which we'd now be able to reproduce in one feature but not the other. Rejected: the *rendering* seam is already shared in `HighlightRenderer`, so the duplication would only cover the driver plumbing, and half-shared / half-duplicated is the worst of both worlds.
- **Push `SentenceSource` up so Cadence and Readaloud own their own DOM injection.** Keeps the controller purely a "render + follow" utility. Rejected: injection and identity-generation are tightly coupled — the source produces `FragmentRef`s that only mean something if the corresponding `<span>` exists in the DOM — so keeping them together is the invariant we want to encode.
- **Collapse `SentenceSource` + `SentenceTicker` into a single `SentencePlayer` interface.** Fewer interfaces. Rejected: Readaloud's source (Sidecar) and ticker (audio clock) are asynchronously produced — the source is ready when the Sidecar is cached, the ticker is ready when audio starts — and merging them forces one lifecycle onto two independent readiness conditions.
- **Keep `fragmentRef` as an opaque type instead of `"href#spanId"`.** Marginally more portable if we later switch to Range descriptors. Rejected: the existing renderer contract, the existing readaloud span-id shape, and the DOM injection model all speak `"href#spanId"` today; changing that is unrelated work.
- **Do the refactor in the same PR as Cadence.** Ship one thing. Rejected: the refactor is behaviour-preserving on a load-bearing pipeline; bundling it with a new feature makes the review harder to bisect and the diff harder to trust. Two PRs, refactor first.
