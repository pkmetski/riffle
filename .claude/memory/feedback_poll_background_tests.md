---
name: poll-background-tests
description: "For long-running test/harness runs started via Bash run_in_background or `make harness-test*`, actively poll the output file (or use Monitor) — do not rely on the completion notification alone"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3143be1a-2207-465a-9476-9182dce39420
---

For any long-running test or harness run kicked off in the background (Bash with `run_in_background`, `make harness-test`, `make harness-test-class`, `./gradlew connectedDebugAndroidTest`, AVD-driven smoke tests), do not just wait for the "completion" notification. Periodically `Read` the output file, or — preferably — launch the run under `Monitor` with a filter for AVD boot, install, and per-test pass/fail lines, so progress and failures surface in chat as they happen.

**Why:** these tasks fail silently. The AVD can hang on boot, the APK install can stall, a test can crashloop, or the runner can get stuck without the parent process exiting — the completion notification then either never fires or arrives after the user has already noticed nothing is moving. Polling / streaming makes the failure mode visible immediately instead of after the timeout.

**How to apply:** if the run is expected to take more than ~30s and lives in a background slot, set up streaming first. For harness tests, the canonical recipe is `Monitor` on `tail -f <output-file>` filtered to `BUILD|FAILED|installed|Tests run|AndroidJUnit|com\.riffle\..*Test` — enough signal to catch boot/install/pass/fail without flooding chat. Never tell the user "waiting for it to complete" and then idle.

Related: [[verify-on-device-before-done]], [[harness-run-avd-gotchas]].
