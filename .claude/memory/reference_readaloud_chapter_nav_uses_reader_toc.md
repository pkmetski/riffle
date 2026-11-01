---
name: reference_readaloud_chapter_nav_uses_reader_toc
description: "Readaloud prev/next-chapter is an AUDIO-domain seek (player owns it), NOT reader-TOC navigation — the TOC approach was tried and rejected"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 43246500-89e7-49e1-ae9a-cba2b522556d
---

Readaloud prev/next-chapter must be an **audio-player concern**: `ReadaloudController.skipChapter` seeks the audio to the adjacent chapter's first clip (`ReadaloudTrack.resolveChapterSkip`) and the reader page follows via the normal auto-follow. No ebook/TOC dependency. This is the ORIGINAL implementation and the one we kept.

**Decision (owner):** the player controls audio chapters; it must NOT be wired to the ebook's TOC/chapter-map. Reason: a future **pure-audiobook player has no ebook, no WebView, no reader TOC** — anything coupled to `railSegments`/`go()`/DOM probing can't serve it.

**Rejected approach (don't redo):** driving prev/next via the reader's TOC (`railSegments`/`activeRailSegmentIndex` → `navigateToSegment`/`ColumnSnap.goAndSnap` → DOM page-top probe → `playFromReaderPosition`). It DID land exactly on a chapter's first sentence, but: (1) couples the player to the ebook reader (fatal for audiobook-only), (2) slow (~3-4s on chapters that open on an un-narrated heading page — extra page-load), (3) fragile timing (every speed-up attempt mislanded / stuck on the heading page). Reverted.

**Known minor quirk of the kept (audio-seek) version:** "chapter" comes from the SMIL clip hrefs (`textFragmentRef` before `#`), i.e. the Storyteller bundle's section/split boundaries. Those are OFFSET from the rendered ABS text (same sentence has a different bundle href than its rendered resource, ADR 0026), so a jump can land ~one sentence into the section rather than dead on the first sentence. Accepted as minor.

**Proper fix (future, its own feature):** give the player real audio-domain chapter markers (positions in the audio timeline) so `resolveChapterSkip` lands correctly while staying a pure seek. Source: the Storyteller bundle's own EPUB3 `nav`/TOC → first clip per chapter (readaloud), or the ABS audiobook chapter list → start times (pure audiobook). Same player code, source-agnostic markers.

Rewind/forward (±15/30s) are a separate, correct pure-audio seek (`resolveRelativeSkip`, global cross-file offset). Diagnosed live on the dev emulator (`emulator-5554`, AVD `Medium_Phone`) with The Martian readaloud set up; WebView DevTools confirmed the bundle-vs-render href offset. See [[reference_readaloud_e2e_test_setup]], [[reference_readaloud_highlight_text_anchored]].
