---
name: no-bigger-ci-runners
description: Fixing iOS harness flakiness/slowness by paying for a larger GitHub runner is off the table — fix it at the test/code level
metadata:
  node_type: memory
  type: feedback
  originSessionId: 9b1180c6-da70-43b7-848a-0026ab644a13
  modified: 2026-10-09T17:51:53.083Z
---

Do not propose (or reach for) a bigger/paid GitHub Actions runner as the fix for iOS harness flakiness or slowness. "Buying hardware is NOT in the cards at all."

**Why:** The user wants the iOS harness to be a single fast, stable job like Android's, achieved through better tests/code — not by throwing money/resources at a 3-core/7 GB `macos-15` runner. Parallel testing (2 simulator clones) must stay; a bigger runner would only *mask* genuinely slow/fragile iOS tests.

**How to apply:** When the iOS harness (`ios.yml` `harness-test-phone`) flakes or a test is slow, root-cause it at the test or app level (interaction patterns, CMP accessibility waits, network client, redundant/compounding `waitForExistence` timeouts) and fix that. Keep `-parallel-testing-enabled YES`. See [[reference_ios_harness_flakiness_root_causes]].

This also covers **timeout increases**: do NOT bump `timeout-minutes` in `ios.yml` to paper over slow tests. The timeout is not the fix — the test or the code causing slowness is the fix.
