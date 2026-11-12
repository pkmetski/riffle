---
name: reference_readaloud_chapter_flash_dateline_collision
description: "Readaloud chapter-crossing flash (reader shows old chapter, corrects on 2nd sentence) = autoFollowSnapJs 12-char key collides with recurring 'LOG ENTRY: SOL N' datelines"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 6f2d409a-a1ef-40c1-9189-0a81b9fa7d0a
---

Root cause of the "advance to next chapter → reader briefly shows the OLD chapter, self-corrects only on the 2nd sentence" bug (branch pkmetski/readaloud-chapter-flash). Reproduced + diagnosed on The Martian via WebView DevTools + bundle SMIL, NOT via a clean live screencap (the headless API-25 AVD crashed 3× and stalled audio under the polling load — a real device is the reliable env).

**The mechanism (paginated readaloud, real chapter boundary):**
1. Each Storyteller split file (`text/part00NN_split_00M.html`) is its OWN Readium spine resource; the reader renders these split files directly and their rendered hrefs == the bundle hrefs (DevTools confirmed `readium_package/text/part00NN_split_00M.html`). So every chapter advance crosses a *rendered resource* boundary and `go(locator)`'s bundle href resolves correctly.
2. The new chapter's FIRST narrated sentence in The Martian is a recurring dateline — `id236-s0` = "LOG ENTRY: SOL 38". Nearly every chapter opens this way ("LOG ENTRY: SOL N"), all sharing the first 12 chars "LOG ENTRY: S".
3. `ColumnSnap.autoFollowSnapJs(text)` matches only `key = text.trim().take(24).slice(0,12)` = "LOG ENTRY: S" via a TreeWalker over the CURRENTLY VISIBLE resource. At the boundary the visible resource is still the OLD chapter (e.g. part0009_split_005, which contains "LOG ENTRY: SOL 37"). The 12-char key matches that OLD dateline → probe returns "on" → snaps to the old log-entry's column → **`go()` is suppressed, reader stays on the old chapter** ("briefly shows beginning of current chapter").
4. The 2nd sentence `id236-s1` = "I'm still cowering in the rover…" is unique → key "I'm still co" not in the old resource → probe returns "off" → `go(locator)` fires → **navigates to the new chapter (self-corrects on the 2nd sentence).**

**Why prior theories were wrong:** NOT an id collision — The Martian's 11689 sentence-span ids are all distinct book-wide, so `ReadaloudTextQuotes.build`'s bare-id keying is fine here. NOT the ADR-0026 bundle-vs-render href offset — for this book the hrefs match. It's a *text-anchor* collision in the auto-follow probe.

**Fix (IMPLEMENTED on branch):** rewrote `ColumnSnap.autoFollowSnapJs` to match the WHOLE sentence instead of a 12-char prefix. To keep the divergence-tolerance the old short prefix gave (the reason it was short — see ReaderWebViewScripts comments: inline-markup node splits + smart-quote/dash/edition drift between served ABS prose and bundle text), it matches over a CANONICAL concatenation of the body's text nodes (whitespace collapsed; curly quotes/dashes folded to ASCII) with an index map back to (node, offset) for the rect. Not found on the current resource → "off" → caller go()s. Added AutoFollowJsTest cases (dateline collision → off; dateline-present → on; node-split sentence → on); 12/12 pass on Harness_Medium_Phone (API25/Chrome55), JVM `./gradlew test` green. NOT committed (awaiting user). Sibling probes `firstVisibleSentenceJs`/`resolveSelectionSentenceJs` still use the 12-char prefix but are single-resource (datelines don't recur within one doc) so weren't touched. Supersedes the "minor ~1-sentence offset quirk" framing in [[reference_readaloud_chapter_nav_uses_reader_toc]] — that quirk is about skip-button landing; THIS is the chapter-crossing flash. See [[reference_readaloud_highlight_text_anchored]], [[reference_readaloud_no_audio_headless_emulator]].
