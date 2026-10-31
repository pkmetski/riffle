---
name: mupdf-no-builtin-selection
description: "MuPDF Android was actually executed as a renderer swap and DISCARDED because it was incompatible with Android 7.1.1 (API 25 — Riffle's Harness AVD baseline, near minSdk 24). Separately, the modern maintained artifact ships no text selection. Do NOT re-recommend MuPDF for Riffle without a minSdk bump AND API-25 compatibility proof."
metadata:
  node_type: memory
  type: reference
  originSessionId: 97cb9cc6-465c-4d87-b6c0-806e5874a3f0
---

**PRIMARY BLOCKER (verified by user 2026-07-02):** MuPDF Android was implemented end-to-end on a throwaway branch (see plan `docs/superpowers/plans/2026-06-29-mupdf-renderer-swap.md`, now marked ABANDONED). Rendering worked on modern APIs but **broke on Android 7.1.1 (API 25)** — that's the Harness AVD baseline and close to Riffle's minSdk 24 floor. The incompatibility surfaced only after the swap was wired in end-to-end, so the work was thrown away. Reviving MuPDF requires either bumping minSdk past API 25 or proving compatibility on the API-25 Harness AVD before writing feature code on top of it.

**SECONDARY BLOCKER (spike, 2026-06-29):** The modern Artifex maven artifact `com.artifex.mupdf:viewer:1.15.+` (from `http://maven.ghostscript.com`) exposes `ReaderView`, `PageView`, `DocumentActivity`, search-highlight rendering, outline, and pinch-zoom — but **no text selection**, no drag handles, no ActionMode. Verified by reading `lib/src/main/java/com/artifex/mupdf/viewer/PageView.java` on master (2026-06). The selection-with-handles UX lives only in the archived `mupdf-android-viewer-old` repo (~2017 Java, AGPL, never packaged as a library artifact); porting is ~3–5 days against dead code, not "drop-in".

The truly-free-selection routes for Android PDF that remain in play:
- **PDF.js in WebView** — attempted (see [[pdfjs-renderer-swap]]); also never merged.
- **`androidx.pdf` (Jetpack PdfViewerFragment)** — free platform selection, but requires **minSdk 31**.

**Don't recommend MuPDF as a drop-in for Riffle.** Two independent blockers (API-25 incompat + no selection in the maintained artifact) each kill it on their own.
