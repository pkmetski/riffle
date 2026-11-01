---
name: reference_readaloud_degenerate_boundary_clip_skip
description: "Readaloud 'sentence skipped, no highlight, jumps to next chapter while audio still speaks it' = a degenerate (~50ms) SMIL clip on the last sentence of a resource whose real audio is absorbed into the next chapter's first clip; bundle-data defect, NOT a Riffle bug, NOT the sentence's characters"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 36a0360f-6a71-4ad7-91de-69c1a1033c79
---

Symptom (The Martian, end of chapter 15): the transcript line **"[16:03] WATNEY: How'd the launch go?"** gets NO highlight and the reader jumps straight to chapter 16 while the audio is still speaking that sentence. User suspected the special characters (brackets, curly apostrophe) — DISPROVEN.

**Root cause = bad SMIL alignment in the Storyteller bundle, not Riffle code.** Verified by range-extracting the bundle (book 1385738337074647) and reading the SMIL/HTML directly:
- The sentence is one clean span `<span id="id33-s626">` (timestamp in-span, id matches `.*-s\d+`, apostrophe U+2019 — all handled fine). It is the LAST `<par>` of `text/part0020.html` / `MediaOverlays/part0020.smil`.
- Its audio clip is **degenerate: clipBegin 5872.280s → clipEnd 5872.330s = 50 ms** on `Audio/00001-00003.mp3`. A multi-word sentence can't narrate in 50ms.
- The NEXT `<par>` is `id34-s0` = "CHAPTER 16" in the NEXT resource `text/part0021.html`, clip `5872.330 → 5886.660s = 14.33s` (the single longest first-par in the whole book). The sentence's real ~2s of narration was absorbed into this next clip.

**Mechanism (faithful following of bad data):** the player follows the audio clock via `ReadaloudTrack.activeClipAt` (half-open `[begin,end)`). At ~5872.33s the active fragment advances from `id33-s626` (held only 50ms → too short to paint/perceive a highlight → "no highlight") to `id34-s0`, whose href is `part0021.html` (chapter 16). The auto-follow probe `ColumnSnap.followNarratedSentence` can't find that text on the current page (chapter 15) → returns "off" → caller `go(chapter16 locator)` → **navigates to chapter 16 while the audio (living inside the id34-s0 clip) is still saying "How'd the launch go?"**. See [[reference_readaloud_highlight_text_anchored]], the EpubReaderScreen follow effect (~L1262), `ColumnSnap.autoFollowSnapJs`.

**Not systematic but recurs:** only **4 of 137** smil files have a last-par clip < 0.2s (part0019_split_004 0.050s, part0020 0.050s, part0025_split_004 0.001s, part0026_split_001 0.010s). In 2 of 4 the following first-par is anomalously long (14.3s, 9.9s) — clear absorbed-audio. Consistent with [[reference-storyteller-v2-and-range-extraction]]: many bundles are under-produced yet labeled ALIGNED; `status` is worthless as a health signal.

**Fix status: none yet — it's upstream data.** Any Riffle mitigation is heuristic (we don't know where within the 14.33s clip the s626 words actually end). Candidate: at track-build time detect a degenerate sentence clip (e.g. <~200ms) and either suppress the premature cross-resource `go()` it triggers or borrow ~1s from the next clip so the highlight is visible and the chapter jump is delayed. Clean seam = pure-Kotlin `ReadaloudTrack` clip logic (unit-testable). Discuss with user before implementing — 3% of boundaries, imperfect by construction.
