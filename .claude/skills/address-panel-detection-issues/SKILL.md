---
name: address-panel-detection-issues
description: Use when asked to work on GitHub issues labeled "panel-view-issue" — fetches each issue, downloads attached comic page images, writes a real-image regression test that reproduces the detection failure, fixes PanelDetector.kt, and deletes the issue once the test passes.
---

# Address Panel Detection Issues

Work through all open GitHub issues labeled `panel-view-issue` one at a time. Each issue reports a comic page where the detector returns the wrong panel count, wrong regions, or a false Fallback. The fix cycle is: **prove the repro is faithful (2b-fidelity) → run the CURRENT algorithm on the sample and check agreement with the user report (2b-agreement) → reproduce with a real-image test → fix the algorithm → pass → device-verify with cache audit (2g-device) → delete**.

**The iron rule: no conclusion from an unverified reproduction.** Every diagnosis must rest on
output you reproduced coordinate-for-coordinate against device ground truth (the issue's
"Detected panels" list or the pulled `files/comic-panels/*.json` cache). If your fixture, path,
book, or page hasn't been proven to match what the device actually did, you are guessing.

## Repo context

| What | Where |
|---|---|
| Detection algorithm | `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/comic/panel/PanelDetector.kt` |
| Synthetic tests | `core/domain/src/jvmTest/kotlin/com/riffle/core/domain/comic/panel/PanelDetectorTest.kt` |
| **Real-image tests** | `core/domain/src/jvmTest/kotlin/com/riffle/core/domain/comic/panel/PanelDetectorImageTest.kt` |
| Fixture images | `core/domain/src/jvmTest/resources/panel-detection-fixtures/<filename>.png` (source-controlled) |
| Run tests | `export JAVA_HOME="$(ls -d /Applications/Android\ Studio.app/Contents/jbr/Contents/Home 2>/dev/null || /usr/libexec/java_home)"; ./gradlew :core:domain:jvmTest --tests "*PanelDetector*"` |

## Phase 1 — Fetch issues

```
mcp__github__list_issues(owner="pkmetski", repo="riffle", labels=["panel-view-issue"], state="open")
```

**Sequencing and isolation rules:**

- **Investigation** — issues may be investigated in parallel, but each in complete isolation: download its fixture, reproduce the failure, and diagnose the root cause independently. Do not let one issue's diagnosis bleed into another's.
- **Fixes are always applied in sequence** — finish the full fix cycle (2a–2h) for one issue before starting the next. Never apply two algorithm changes at once.
- **Cross-issue fixes** — it is possible that fixing one issue incidentally fixes another. If you believe this is the case, do not close the second issue on theory alone. You must: (1) run the second issue's fixture through the updated algorithm, (2) confirm the output no longer exhibits the reported failure per the 2f gate, and (3) verify no new regression was introduced. Only after all three pass may you close the second issue citing the first fix.

Work through each issue sequentially; do not batch-fix.

## Phase 2 — For each issue

### 2a. Extract and commit the image

The panel reporter uploads a **binarized mask PNG** (black=content, white=gutter — copyright-safe per ADR 0062) as a secret gist. The issue body contains two lines:

```
**Mask fixture (gist):** https://gist.github.com/pkmetski/<gist-id>
**Mask raw URL:** `https://gist.githubusercontent.com/pkmetski/<gist-id>/raw/mask.b64`
```

Download and decode the mask with:

```bash
curl -sL <raw_url_from_issue_body> | base64 -d > core/domain/src/jvmTest/resources/panel-detection-fixtures/issue-<N>-<short-description>.png
```

(On macOS, `base64 -d` is `base64 -D`.)

Save the decoded PNG to:

```
core/domain/src/jvmTest/resources/panel-detection-fixtures/issue-<N>-<short-description>.png
```

This directory is source-controlled — the fixture travels with the test so CI always has it. **Never leave a test that conditionally skips when the image is absent** — a skipped test provides no regression protection.

**Deduplicate before committing.** Multiple reports about the same page produce byte-identical
masks (issues #879/#892/#893/#894/#895/#905 were SIX copies of one page-34 mask). Hash the
download against the existing fixtures first:

```bash
md5 -q /tmp/new-mask.png core/domain/src/jvmTest/resources/panel-detection-fixtures/*.png | sort | uniq -c
```

If an identical fixture already exists, point the new test at the existing file (add a comment
naming both issues) and do not commit a second copy. If you find pre-existing duplicates while
doing this, consolidate them: keep one file, repoint every test, `git rm` the rest.

If the issue has no attached image, comment asking the reporter to attach one, then skip to the next issue.

### 2b. Understand the expected outcome

Read the issue body carefully:
- How many panels does the user expect?
- Is the problem a false Fallback, wrong count, wrong reading order, or a crash?
- Note the expected `PanelSource` (`Auto` for real detection, `Fallback` for whole-page).

### 2b-fidelity. MANDATORY fidelity gate — reproduce the reported output BEFORE writing any test

The issue body's **"Detected panels"** list is the exact output the device produced. Your
reproduction is only trustworthy when running the fixture through `detector.detect()` (with
`originalWidth = grid.width, originalHeight = grid.height`) reproduces that list
**coordinate-for-coordinate** (±2px is acceptable — the binarizer's border handling of a
pre-binarized two-value mask differs slightly from grayscale luma).

**The gate also selects the LOADER — run BOTH loaders and keep the one that matches.** There
are two ways to feed a mask fixture into `detect()`, and they exercise DIFFERENT pipelines:

- `loadMaskFixture` → luma `PixelGrid` → `detect(grid, …)`: runs binarization plus every
  luma-dependent stage (`energyValleySplit` and any other stage that reads the grid). This is
  the pipeline the device runs.
- `loadBinaryFixture` → `PanelBinaryMask` → `detect(mask, …)`: bypasses binarization AND passes
  `grid = null`, silently skipping all luma-dependent stages.

Neither is universally faithful: on issue #905's page only `loadMaskFixture` reproduced the
device output — the `loadBinaryFixture` tests for #893/#894/#895 were green against a pipeline
the device never runs, which is exactly how the #894 "fix" shipped while the device kept
failing. On issue #835's textured page the opposite held (`loadMaskFixture`'s re-binarization
expanded texture and changed the path; `loadBinaryFixture` was the faithful one). So: run the
fixture through BOTH loaders, compare each against the issue's "Detected panels", and write the
regression test with whichever loader reproduces the device coordinate-for-coordinate. A test
written against the non-matching loader is not a regression test — it pins a pipeline the
device does not execute.

- **Match (with the chosen loader)** → the repro is faithful; proceed to 2c.
- **Mismatch with BOTH loaders** → STOP. Do not touch the algorithm. Every conclusion drawn from an unfaithful
  fixture is a guess. The #783/#784 fiasco (two failed fix rounds, one new regression, wrong
  book verified on device) happened because the fixtures diverged from the detection input and
  nobody checked. Find the divergence first:
  1. Identify the book: the report does not name it (no-real-titles policy). Pull the device's
     panel caches (`adb shell run-as com.riffle.app ls files/comic-panels/`) and match the
     issue's "Detected panels" against a cached page — that gives you `bookId::sourceBookId`
     and `pageIndex`.
  2. Fetch the ORIGINAL page bytes (Komga: `GET /api/v1/books/<id>/pages/<pageIndex+1>`,
     credentials in memory) and replicate the device pipeline on JVM: full-res decode → float
     BT.601 luma (`0.299/0.587/0.114`, exactly `AndroidPageImageDecoder`) → `detect()`. This
     MUST reproduce the reported list exactly. Regenerate the fixture from this pipeline
     (`PanelMaskBinarizer.binarize(grid)` → encode black/white PNG) and use THAT as the fixture.
  3. If even the JPEG replication mismatches, the installed APK diverges from your working tree
     or the cache is stale — resolve that before proceeding.

Since the `generateMaskPng` fidelity fix (2026-08-21), reporter masks are produced by the exact
detection pipeline, so new fixtures should pass this gate on the first try. The gate stays
mandatory anyway — it is the only proof the repro is real.

**The binarized mask is SUFFICIENT to detect, fix, and verify the bug.** Once it passes this
gate it IS the detection input — the failing regression test, the diagnosis, the fix, the
boundary tests, and the JVM verification all run on the mask alone. Do not fetch, request, or
hunt for the original page image as part of the normal cycle; the original may not be
available or discoverable at all, and that never blocks a fix (it only degrades 2g-device —
see there). The #787 fix (2026-08-22) was completed end-to-end on the issue's mask.

The ONLY situation that calls for original page bytes is a fidelity-gate FAILURE — the mask
does not reproduce the issue's "Detected panels" ±2px **with either loader**. Before concluding
anything, re-check the loader choice above (the #905 gate "failure" was really the binary-mask
loader exercising the wrong pipeline). Only if both loaders genuinely mismatch may you fall back
to fetching the original page to replicate the device decode (full-res float BT.601 →
`detect(grid)`) — a diagnostic last resort, never part of the normal cycle, and the original may
not be available online at all; if it isn't, document the mismatch and proceed with the loader
whose output is closest. A remaining mismatch signals something genuinely broken (installed APK
diverges from the working tree, stale cache) that must be diagnosed, not routed around.

### 2b-agreement. CRITICAL — run the CURRENT algorithm on the sample image and compare against the user's report

After the fidelity gate, run the fixture through the current working-tree algorithm and put its
output side-by-side with the TWO lists in the issue body: the "Detected panels" (what the device
produced) and the "Expected panels (user-drawn)" (what the user says is right). Then answer
explicitly, in writing, before doing anything else:

1. **Does the current output AGREE with the reported-bad "Detected panels" list (±2px)?**
   - **Yes** → the bug is LIVE in the current code. This holds even if the coordinates also
     match a regression test's *expected* values — that means the test itself pinned the wrong
     behaviour, not that the fixture is stale. (#787: the report's x=592 row2-right exactly
     matched the #784 fix's own "correct" test assertion; the report was filed AFTER the fixed
     build was installed. The fix's output was itself the defect.)
   - **No** → the current code already produces something different. Do NOT conclude "already
     fixed" from this alone — proceed to step 3.
2. **Does the current output satisfy the user's expectation?** Check every user-drawn panel: is
   there a detected panel matching it (position and extent, not just count)? "Different from the
   reported-bad output" ≠ "matches what the user drew". Only the user-drawn list defines fixed.
3. **Check the timeline.** Compare the issue's `created_at` against the commit/install time of
   any fix you believe covers it. A report filed after the fix was installed on the device is
   evidence the fix did NOT resolve the user's complaint — never evidence of a stale cache or
   stale mask.

**Never rationalize a disagreement away.** If the fixture reproduces the current algorithm's
output within ±2px, the fixture is faithful and the output it shows is real — deleting the
fixture or the failing test because "the mask must be from the old pipeline" is exactly the
motion that let #787 be wrongly closed. Closing/deleting an issue as "already fixed" requires
BOTH: current output disagrees with the reported-bad list AND matches the user-drawn expectation
AND the timeline is consistent (report predates the fix).

### 2c. Write a failing regression test

Add a `@Test` to **`PanelDetectorImageTest.kt`** (not `PanelDetectorTest.kt` — that file is for synthetic/programmatic tests only). Use the loader that passed the 2b-fidelity gate for THIS fixture (usually `loadMaskFixture`, which maps black→content, white→gutter, runs the device's grid path, and throws loudly if the file is absent — but see the loader-selection rule in 2b-fidelity):

```kotlin
@Test
fun `<short description of the page layout and bug>`() {
    // Regression for issue #<N>: <one-sentence description of failure>.
    val grid = loadMaskFixture("panel-detection-fixtures/issue-<N>-<short-description>.png")
    val result = detector.detect(grid, pageIndex = 0, originalWidth = grid.width, originalHeight = grid.height)
    assertEquals(PanelSource.Auto, result.source)
    assertEquals(
        "expected <N> panels for <layout description>, got ${result.panels.size} (source=${result.source})",
        <N>, result.panels.size,
    )
}
```

**No `if (!file.exists()) return` or any other conditional skip.** A test that silently skips provides zero regression protection — it's worse than no test because it creates false confidence. `loadMaskFixture` throws `IllegalStateException` if the fixture is absent, which is the correct behaviour.

Run the test. **It must fail (RED).** If it passes already, the image doesn't reproduce the issue — ask the reporter for a different page.

### 2d. Diagnose the failure

Read `PanelDetector.kt` end-to-end before touching it. The algorithm has 9 pipeline stages; failures are usually in one of:

- **Binarization** — wrong background estimate → gutter pixels classified as content or vice versa. Check `estimateBackground()` (85th percentile of border samples). Dark scanner corners drag the estimate down; coloured/textured backgrounds can push it up.
- **Projection grid** — `gridByProjection()` returns wrong cells when the gutter is too narrow (< 15 px) or when a row band is "suspicious" (spans full width). Check whether all rows are flagged suspicious and the algorithm falls through to CC incorrectly.
- **Flood fill / CC** — gutter not flood-fill-reachable from border → panels merge. Add `[DEBUG-panel]` log lines around the BFS to trace which pixels block reachability.
- **filterAndTighten / splitAtInternalGutters** — tiny gutter → split fails; or internal gutter is too thick → false split.
- **Global sanity checks** — check `applyGlobalSanityChecks()`. If `overlapRejectFraction` or `minTotalCoverageFraction` triggers Fallback on a valid page, the thresholds need tightening — but prefer a targeted fix over widening a global threshold.

Use the schema version history in `JsonPanelStore` to understand what each past fix addressed. Don't reopen old bugs.

### 2e. Fix PanelDetector.kt

- Make the smallest change that passes the new test without breaking any existing test.
- If you add or tighten a `Config` parameter, document it in the parameter table in the ADR or inline KDoc.
- If the fix changes detection output for cached pages, bump `JsonPanelStore.CURRENT_SCHEMA_VERSION` by 1 — the cache will self-invalidate.

### 2f. Verify the fix against the actual image

**Required gate — complete ALL three steps before announcing the issue is fixed:**

1. **Get the problematic image** — read the fixture PNG from `core/domain/src/jvmTest/resources/panel-detection-fixtures/issue-<N>-*.png` with the `Read` tool to see the actual page layout.
2. **Run it through the panel algorithm** — execute `./gradlew :core:domain:jvmTest --tests "*PanelDetectorImageTest*"` and capture the panel coordinates the detector produces for this fixture.
3. **Analyse the report** — examine the algorithm output from step 2 and check whether the reported issue is still present:
   - If it IS still present → the issue is **NOT fixed**. Do not announce a fix; go back to 2d.
   - If it is NOT present → confirm the specific panel reported as missing IS now in the result, no previously-correct panels have disappeared, and coordinates are geometrically plausible. Only then is the issue fixed.

Tests may only cover full-scale behaviour; the bug may persist at device scale (`inSampleSize=2`). When the full-scale test passes but the device-scale behaviour is unknown, add a second test that downscales the fixture (2× subsample) and asserts the corrected result, then verify that test passes too.

**Do not announce the issue as fixed until step 2f shows it is actually fixed.**

### 2f-boundary. Threshold changes require boundary tests

If the fix adds or changes ANY numeric threshold (a width fraction, a gap size, a shift floor),
add synthetic tests pinning BOTH sides of the boundary: one input just inside the trigger zone
(fires) and one just outside (does not fire). The #786 regression shipped because the merge fix
was only tested with inputs where it was *intended* to fire — an input at 53% width (below the
band threshold that didn't exist yet) hit the same code path and destroyed a valid layout.
Additionally, enumerate what OTHER real layouts share the geometric signature the fix keys on;
if two real layouts are geometrically indistinguishable, the fix needs a content-based
discriminator (see `boundaryContinuesThroughColumn` for the pattern), not a tighter threshold.

### 2g. Confirm GREEN

```
./gradlew :core:domain:jvmTest --tests "*PanelDetector*"
```

All tests must pass including the new one and all pre-existing ones.

### 2g-device. Device verification with cache audit — REQUIRED before delete

JVM green is necessary but not sufficient. Verify on the emulator (own AVD by serial):

1. Build (`:app:assembleDebug`), `adb install -r`, and delete the book's panel cache files
   (`run-as com.riffle.app rm files/comic-panels/<file>.json`) — or rely on the schema bump.
2. Open THE BOOK THE ISSUE CAME FROM (identify it via the cache-match from 2b-fidelity — do
   not assume; the #783/#784 round was "verified" in the wrong book) and page through the
   reported page plus ~5 pages on each side in Panel View.
3. Pull the regenerated cache JSON and diff it against the expected panel list — this is the
   authoritative geometry check, not screenshots. Then geometry-audit EVERY freshly detected
   page: no sliver panels (< 12% page width), no cross-row talls (h > 60% with w < 55%), no
   out-of-bounds boxes, plausible counts.
4. Screenshot the specific panel(s) the user reported for visual confirmation.
5. If any other book was affected by the changed code path (check which fixtures/pages went
   through it), repeat the sweep there.

Device verification exercises the pipeline (schema invalidation → decode → detect → cache
write), not the image — the emulator opens the book itself. If the source book is unavailable
or cannot be identified, do NOT hunt for the original page: the mask-based JVM tests remain the
algorithm verification, and the device check degrades to confirming the schema bump invalidates
old caches plus a sweep on any available book that exercises the changed code path.

### 2g-schema. Bump the cache schema

Any change to detection output requires bumping `JsonPanelStore.CURRENT_SCHEMA_VERSION` with a
history entry describing what stale caches hold. Without it devices keep serving the old wrong
panels and the fix looks broken.

### 2h. Delete the issue — NOT close, DELETE — ONLY if actually fixed

**STOP before deleting. You must be able to answer YES to every item below. If any answer is NO, do NOT delete — leave the issue open, add a comment describing what was attempted and what remains, and move to the next issue.**

**The only reason to delete an issue is that it was actually fixed.** If you could not fix the issue — the algorithm cannot handle this layout, the fix regresses other tests, the bug is a known limitation, or you ran out of diagnostic leads — do NOT delete the issue. Leave it open, add a comment describing what was investigated and why it remains unresolved, and move on.

Pre-deletion checklist — every item must be YES, or do not delete:
- [ ] **2f passed**: `PanelDetectorImageTest` for this issue runs GREEN with the new fixture — and the detector's output for that fixture now matches the user-drawn expectation (count, positions, source), not merely "different from the reported-bad output".
- [ ] **2g passed**: All `*PanelDetector*` tests pass, including all pre-existing ones.
- [ ] **2g-device passed** (or explicitly documented as degraded): Device verification was completed on the correct book, or the source is unavailable and the degraded-mode sweep is documented.
- [ ] **The fix was applied in this session** — do not delete an issue whose fix was merged in a prior branch that you have not re-verified against the current working tree.

If device verification was skipped or degraded, say so explicitly in the closing comment.

**Use `gh issue delete <N> --repo pkmetski/riffle --yes`** — this permanently deletes the issue from GitHub, not just closes it. Comment first (step below), then delete. Never use `gh issue close`; the label-based workflow uses deletion to signal "shipped".

Comment with a fix summary first, delete the linked gist, then delete the issue:

```
mcp__github__add_issue_comment(
  owner="pkmetski", repo="riffle",
  issue_number=<N>,
  body="Fixed in <branch>. Added regression test `<test name>` in `PanelDetectorImageTest.kt` that reproduces the failure; passes after the algorithm fix."
)
```

Delete the gist linked in the issue body **before** deleting the issue (the gist URL is no longer reachable once the issue is gone, making orphaned gists unidentifiable):

```bash
gh gist delete <gist-id>
```

```bash
gh issue delete <N> --repo pkmetski/riffle --yes
```

## Common failure patterns and fixes

| Symptom | Likely cause | Fix direction |
|---|---|---|
| Fallback on a clearly-panelled page | Background estimate too dark → gutter reads as content | Adjust border sampling or contrast threshold |
| Too many panels (page split in half) | Internal gutter threshold too aggressive | Raise `internalGutterMaxFraction` or `internalGutterFloodFillFraction` |
| Too few panels (adjacent panels merged) | Gutter not flood-fill-reachable | Check if gutter is closed off by a stray pixel; fix `splitAtInternalGutters` |
| Projection returns 1 huge cell instead of grid | All rows suspicious (each spans full width) | Narrow the "suspicious" threshold or handle partial-width splash separately |
| Panel top/bottom cut off | Caption box touching border lets flood-fill in; thin margin below caption triggers false split whose smaller piece is filtered | Guard in `splitSinglePanelRecursively`: skip split if either sub-bbox < `minPanelDimensionFraction` of page |
| Wrong reading order | Panels not clustered into row bands correctly | Check `PanelOrderer` y-overlap tolerance (50%) |

## Red flags — stop and diagnose

- `loadMaskFixture` throws `IllegalStateException: Fixture not found` — the image wasn't copied to the right resources path. Fix the path; never add a conditional skip.
- You wrote `if (!file.exists()) return` or `?: return` anywhere in a test — delete it. Silently skipping is not acceptable; it lets regressions go undetected on CI.
- The new test was added to `PanelDetectorTest.kt` instead of `PanelDetectorImageTest.kt` — move it.
- New test passes immediately without any code change — the image doesn't reproduce the issue; get a different attachment.
- Fixing this issue breaks an existing test — you are regressing a prior fix; scope your change more narrowly.
- You are widening a global sanity-check threshold — prefer a targeted algorithmic fix; widening thresholds hides future failures.
