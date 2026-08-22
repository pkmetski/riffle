# ADR 0062 — Panel Detection Reporting System

**Date:** 2026-08-18
**Status:** Accepted

## Context

The on-device panel detector (ADR 0055) is a heuristic algorithm that fails on real-world pages whose structure falls outside its tuned parameters. Failure cases accumulate as new comics are read. Previously there was no systematic way to:

1. Report a detection failure in a reproducible form.
2. Build a regression test corpus from real pages without committing copyrighted comic art.
3. Classify and catalogue failure patterns so fixes can be tracked.

The two existing scanned-page tests in `PanelDetectorTest` load real images from `.context/attachments/` and skip on CI — they are not stable regression fixtures.

## Decision

### Sanitized Page Mask

When a detection failure is reported, the app binarizes the affected page using the same `binarize()` step the detector runs internally. The result — one bit per pixel, content = black, gutter/background = white — is saved as a PNG. All original art is discarded; only the structural signal the detector operates on is preserved.

This **Sanitized Page Mask** is the canonical regression fixture format. It is copyright-safe to commit to the repository and is sufficient to reproduce any detection outcome because `PanelDetector` operates entirely on the binarized mask after `binarize()` completes.

Masks are stored in:
```
core/domain/src/jvmTest/resources/com/riffle/core/domain/comic/panel/fixtures/
```

Each fixture test in `PanelDetectorTest` loads a mask PNG, reconstructs a `PixelGrid` (white → luma 240, black → luma 20), runs `PanelDetector.detect()`, and asserts:
- `source == PanelSource.Auto` (or `Fallback` when that is the expected correct result)
- `panels.size == N`
- Each expected panel has its centre in the correct screen quadrant (top-left, top-right, bottom-left, bottom-right, or a band such as top-strip / bottom-strip for multi-row layouts)

Quadrant assertions are more robust than pixel-coordinate assertions across minor algorithm tuning, while still catching the real failure modes (wrong count, misplaced region, unexpected Fallback).

### In-App Reporting UI

A "Report panel detection issue" item is added to the CBZ reader's overflow menu (⋮). It is gated on `developerModeEnabled` — a DataStore flag — so it is invisible to regular users. The item appears regardless of whether Panel View is currently on; detection runs on every page open and can produce bad results even when Panel View is toggled off.

**Report form contents:**
- **Failure type** (picker): Missed panel / Merged panels / Wrong panel count / False panel / Fell back to full page / Cut panel cut off
- **Optional notes** (free-form text)
- **Tappable Sanitized Page Mask preview**: the binarized mask is rendered with detected `PanelRegion` outlines overlaid. The user taps to identify the problematic area. A tap inside a detected region selects that region. A tap outside all regions drops a free-point marker — used for Missed panel, where no outline exists to tap. Both interactions record the tapped coordinates in the report.

The failure type becomes a GitHub issue label. The tapped coordinates appear in the issue body as pixel positions relative to the page, giving the developer a precise location without requiring them to guess from the description alone.

### GitHub Issue Creation

Reports are filed programmatically without opening a browser:

1. The Sanitized Page Mask PNG (base64-encoded) and a metadata JSON file are uploaded as a secret gist via `POST /gists`. The gist description names the failure type and page index for self-containment.
2. The issue body links the gist HTML URL and embeds the `raw_url` of the `mask.b64` file so the `address-panel-detection-issues` skill can download and decode the fixture in one `curl` call.
3. `POST /repos/pkmetski/riffle/issues` creates the issue with title, body, and the failure-type label.

The PAT is stored in `EncryptedSharedPreferences` and entered once via the Developer Options section in Settings. It requires `public_repo` and `gist` scopes.

Masks are ~100–220 KB base64, well under the 1 MB gist file truncation threshold; the `raw_url` fallback covers any larger masks.

### Developer Options

Tapping the app version number in Settings 7 times sets `developerModeEnabled = true` in DataStore and reveals a **Developer Options** section at the bottom of the Settings screen. The section contains the PAT input field (obscured text, paste-friendly). Tapping the version 7 more times disables developer mode. The unlock gesture and the section are present in both debug and release builds, so the reporting tool is usable on personal devices running release APKs.

## Alternatives considered

**Luma-only greyscale PNG as the fixture format:** preserves more signal for texture-based detection paths but still renders the original artwork (desaturated). Rejected — copyright risk remains, and the binarized mask captures exactly the signal `PanelDetector` acts on after its first step.

**Imgur for image hosting:** anonymous upload API requires only a client ID; simpler. Rejected — third-party permanence is uncertain; gists keep everything within GitHub with one API call.

**`panel-reports` branch in this repo (original implementation):** git blob → tree → commit → ref update, with a 422-retry loop for concurrent submissions. Replaced — the branch polluted the product repo, required five API calls, and could not be deleted permanently (auto-recreated from main). Gists are individually deletable, require one call, and carry the `gist` PAT scope rather than `public_repo`.

**Browser-based issue filing (`issues/new?body=...`):** requires no PAT configuration. Rejected — cannot attach the image automatically; the user would need to drag the PNG into the browser manually, breaking the single-tap submit flow.

**`BuildConfig.DEBUG` gate for the report menu item:** invisible in release builds. Rejected — the developer uses personal devices running release APKs; the secret-tap Developer Options pattern (modeled on Android's own Developer Options unlock) provides equivalent privacy with release-build availability.

**Pixel-coordinate assertions in fixture tests:** more precise than quadrant checks. Rejected — detector output coordinates vary slightly across parameter tuning; quadrant checks catch the meaningful failures (wrong panel, wrong region, unexpected Fallback) without creating brittle tests that break on every config tweak.
