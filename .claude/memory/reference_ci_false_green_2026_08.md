---
name: reference-ci-false-green-2026-08
description: "CI green-washed all failures 2026-07-28→2026-08-11 via TWO mechanisms — emulator-runner per-line sh -c (rc lost) and KMP modules missing from `gradlew test`"
metadata: 
  node_type: memory
  type: reference
  originSessionId: c6c3ebb6-0842-4b10-89f3-916a31f30056
  modified: 2026-08-11T11:34:47.423Z
---

Between 2026-07-28 and 2026-08-11 main's CI reported green while tests failed, via two independent holes (both fixed on `pkmetski/fix-ci-false-green`):

1. **`reactivecircus/android-emulator-runner` executes every `script:` line as a separate `sh -c` process.** Shell variables do NOT survive across lines. The logcat-capture wrapper added in #650 (`rc=0` / `./gradlew … || rc=$?` / `exit $rc` on separate lines) always exited 0 — `exit $rc` ran in a fresh shell with `rc` empty. Fix: keep the gradle call, logcat dump, and `exit` on ONE line. Any multi-line script in that action that relies on shell state is broken the same way.

2. **KMP modules have no `test` lifecycle task** — their JVM tests only run via `jvmTest` (or `allTests`). When core:common/models/domain/sources went multiplatform in #644 (core:net born KMP), `./gradlew test` silently stopped running ~97 test files / 827 tests. CI unit-test job now runs `./gradlew test jvmTest`. Local habit must match: [[feedback-gradle-test-command]].

Consequence: ~21 distinct harness tests were failing on main by 2026-08-11 (7 deterministic regressions from PRs #662/#666/#668/#673 + a flake family from #650's async detail-screen reflow). Diagnosing "when did test X break" is possible without re-running anything: `gh run view <id> --log --job <jobid> | grep 'Execute .*: FAILED'` across the run history builds the onset matrix.
