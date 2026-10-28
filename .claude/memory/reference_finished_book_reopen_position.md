---
name: finished-book-reopen-position
description: "Root causes for \"marked-as-read book reopens mid-book / at back cover\" and \"browsing a finished book drops it out of Completed\"; shared fix = resumePositionFor + isFinishedReadingProgress in core:domain"
metadata:
  node_type: memory
  type: reference
  originSessionId: ec2f16c8-a1df-43ce-ac3c-3a0d70d0812b
  modified: 2026-09-26T05:49:53.404Z
---

Contract: a finished book (readingProgress >= 1.0) ALWAYS reopens from the beginning; browsing it never
changes its 100% / Completed state. Only mark-as-unread restores normal resume.

Three independent write/read paths broke this (2026-09-25/26, branch pkmetski/fully-read-position-stale-progress):
1. `runSyncCycle` LocalWins pushed the in-session fraction (0.28) to ABS → `pullAllProgress` derived
   isFinished=false → book left Completed. Guard: local readingProgress finished → return InSync.
2. `PositionSaveCoordinator.onClose` → `UpdateReadingProgress` wrote Readium's fraction straight into
   `library_items.readingProgress` (the "99%" symptom). Guard in the use case via `LibraryMutator.currentReadingProgress`.
3. `EpubReaderViewModel.serverProgressToLocator` falls back to `locateProgression(ebookProgress)` when ABS has
   no CFI — a mark-as-read record (loc="", progress=1.0) made the reader open at the BACK COVER ("Progress 99%").
   Plus every open site resumed the stored locator regardless of finished state (LLM book reopened at ch05).
   Fix: `resumePositionFor(saved, readingProgress)` at all 7 open sites (Android Epub/Pdf/Cbz repos, O'Reilly
   lazy path, iOS Epub/Pdf/Cbz) and `runSyncCycle` withholds ServerWins for a finished server record.

Diagnosis toolkit that worked: pull the Room DB via `adb exec-out run-as com.riffle.app.debug cat databases/riffle.db*`
and query with local sqlite3; query ABS `GET /api/me/progress/{id}` with a token from `POST /login` (test/test);
read the reader position from the `chapter_navigation_rail` content-desc ("Active rail segment: X. Progress N%").
Reader page turns on the AVD need `input swipe`, not taps (taps toggle the toolbar).
