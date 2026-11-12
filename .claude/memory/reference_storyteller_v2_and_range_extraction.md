---
name: reference-storyteller-v2-and-range-extraction
description: "Storyteller /api/v2/books/{id} returns full ebook+audiobook+readaloud Readium manifests inline (no audio); the synced zip supports byte ranges so SMIL/text are range-extractable (~1.3MB vs 315MB); bundle audio = ABS's same source file re-split into fixed ~57MB segments"
metadata: 
  node_type: memory
  type: reference
  originSessionId: ef3735de-dfbc-403b-b322-1ea8ad444155
---

Empirically verified against media-server (The Martian, Storyteller book 1385738337074647 / ABS item 977c32b7-970c-4225-8f3a-750a47773cfb) while assessing "stream readaloud audio from ABS instead of downloading the 315MB bundle".

**Extra Storyteller endpoints (beyond the ones in [[reference-storyteller-server]]):**
- `GET /api/v2/books/{id}` → ~96KB JSON with `ebook`, `audiobook`, `readaloud` sub-objects, each carrying a Readium **webpub manifest** (`readingOrder`/`resources`/`toc`). `audiobook.manifest.readingOrder` lists the source tracks WITH per-track `duration`, and `audiobook.fileSize`/`duration` match ABS exactly. `readaloud.status` (e.g. `ALIGNED`) + `currentStage` gate availability. This is audio-free structure for ~96KB.
- No per-resource HTTP endpoint exists: `/api/books/{id}/synced/<path>`, `.../GND/*.json`, `.../MediaOverlays/*.smil`, `/manifest.json` all 404 (return the SPA shell). Individual SMIL/text/audio bytes live ONLY inside the `/synced` zip (or server FS at `readaloud.filepath`). No OpenAPI/docs (404).

**Bundle audio layout — Storyteller re-splits, does NOT re-encode:**
- The Martian readaloud zip = 6 STORED mp3 `Audio/00001-0000N.mp3`, 5×~57.5MB + remainder, total 313.7MB. ABS serves the SAME book as ONE file (`Andy Weir - The Martian.mp3`, 313,869,927 B). Storyteller's own `audiobook.fileSize` = 313,869,927 (byte-identical → same source ingested). The 6-segment sum is only 150KB (0.05%) smaller ⇒ stream-copy re-segmentation, not transcode. Split is a FIXED ~57.5MB / ~2h boundary, independent of ABS's file/chapter structure.
- Consequence: SMIL `clipBegin/clipEnd` are relative to Storyteller's segment files. To seek ABS audio: `ABS_abs_time = (Σ durations of prior Storyteller segments) + clipBegin`, then map global time → (ABS track, in-track offset) via ABS per-track durations. Deterministic; segment durations derivable from each segment's max clipEnd or byteSize×bitrate (Martian = 64kbps CBR, 8004 B/s).

**SMIL/text are cheaply range-extractable from /synced (proven E2E):**
- `/synced` returns `accept-ranges: bytes`. Recipe: range-fetch last 64KB → parse EOCD → range-fetch central directory (~27KB) → for each wanted entry parse its local header (30B+name+extra) → range-fetch its compressed bytes → `zlib.decompress(raw,-15)` for DEFLATE (STORED = raw).
- Non-audio total = **1.27MB compressed (0.41% of bundle)**: smil 182KB, html(text) 399KB, jpg/jpeg covers 682KB, opf/css/ncx ~9KB. SMIL alone = 182KB. So full alignment+text for ~0.6MB vs 315MB.

**Segmentation MIRRORS the source's track structure (verified multi-track):** Project Hail Mary — ABS = 32 mp3 tracks; Storyteller = 30 mp3 segments whose per-file max `clipEnd` equals the ABS track durations EXACTLY (track2 2204s=seg 00012, track3 1721=00023, … track9 2190=00032). So clip offsets are PER-TRACK (reset to 0 each track) and segments map 1:1 to ABS tracks. Storyteller DROPS non-narrative tracks (PHM's 21s intro + 44s outro → 32-2=30), so the two timelines are NOT naive concatenations-from-zero — align segment↔track by matching durations, not cumulative sum. Single-file source (The Martian) is the other regime: Storyteller splits the one file into fixed ~57MB/~2h segments with deep offsets. General mapping must handle both via duration-matched segment↔track correspondence. Audio nearly byte-identical & same codec (PHM 466 vs ABS 485MB mp3 = dropped intro/outro + re-mux), NOT re-encoded.

**Many bundles are degraded — surveyed ALL 21 synced books on the test server (2026-06):** 6/21 (29%) are bad, yet ALL 21 report identical `status=ALIGNED, stage=SYNC_CHAPTERS` → **status is worthless as a health signal.** Breakdown:
- 3 FULLY UNUSABLE (zip valid, but ONLY the text EPUB — zero audio, zero SMIL): Small Gods (1.1MB), The Elegant Universe (1.7MB), The Hobbit (4.6MB).
- 3 PARTIAL (alignment incomplete): Guards! Guards! (1 SMIL / 36 chapters, 35MB ≈ ch.1 only); The Dark Forest (8 SMIL / 26 chapters — note AUDIO is fully present at 669MB/36 segs, only the SMIL alignment is short); 1984 (4 SMIL / 9, 91MB, AAC).
- 1 re-encoded to AAC/mp4 (usable, non-mp3): The Fellowship of the Ring (28 mp4 segs). (1984 also AAC.)
- 14 clean & full mp3 (Martian, Prelude, Project Hail Mary, Children/God-Emperor of Dune, Clash/Dance of Dragons, AI Engineering, Forward Foundation, Foundation's Edge, Cosmos, 2001, Outlive, Dragons of Eden).
NOT corruption/bit-rot — the zips parse fine; they're UNDER-PRODUCED (sync failed or stopped partway) yet mislabeled ALIGNED. Lesson: a usability gate must inspect actual audio+SMIL coverage vs chapter count (v2 manifest resources or zip central directory), never trust `status`. Affects Riffle's current download/gate path too, not just a streaming redesign.

Relates to [[project-readaloud-audiobook-sync-gap]], [[reference-readaloud-highlight-text-anchored]] (highlight needs Storyteller sentence TEXT → include the 399KB html in the slim artifact, or anchor to ABS ebook text).
