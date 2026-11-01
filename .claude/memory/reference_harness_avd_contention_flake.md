---
name: reference_harness_avd_contention_flake
description: "Harness \"Process crashed\" failure can be AVD contention from parallel Conductor workspaces, not your code"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 95564342-b2d7-4017-b5ea-39dcca2c8581
---

`make harness-test` boots a shared-by-name AVD `Harness_Medium_Phone`. Conductor runs many workspaces in parallel, so a SECOND workspace booting the same AVD triggers the emulator's `FATAL | Running multiple emulators with the same AVD is an experimental feature` and the instrumentation host dies mid-run → a harness test reports `FAILED` with `Instrumentation run failed due to Process crashed` / `Test run failed to complete`.

Tell-tale signs it's contention, not your change:
- The failing test is unrelated to your diff (e.g. a pure Compose `NavigationDrawerGestureTest` when you only touched the reader) and the failure is "Process crashed", not a clean assertion diff.
- `tail -f /tmp/riffle-emulator.log` (or your boot log) shows the FATAL "multiple emulators with the same AVD" line.
- `adb devices` + `adb -s <serial> emu avd name` shows another `Harness_Medium_Phone` already up.

Fix: confirm no other `Harness_Medium_Phone` is running (`for s in $(adb devices|grep emulator|cut -f1); do echo $s $(adb -s $s emu avd name); done`), then re-run `make harness-test`. The dev's own `Medium_Phone` AVD is a DIFFERENT name and does not conflict. Also: piping `make harness-test | tee | tail` masks the real exit code (you get tee/tail's 0) — redirect to a file and check `$?` instead.

When a sibling can't be cleared (e.g. it's running `Harness_Medium_Phone_2` + the dev's `Medium_Phone`, so 3 emulators contend and the full 173-test run keeps "Process crash"-ing on a *different* unrelated test each attempt before reaching yours), don't keep hammering the shared machine. Use **`make harness-test-class CLASS=<fqcn>`** (sanctioned Makefile target) to boot the dedicated phone AVD and run just your one class, isolated via `ANDROID_SERIAL` to its own serial — verified 2026-06-11 to pass `ReadaloudMiniPlayerTest` cleanly while the full run kept crashing. See [[reference_api25_avd_boot_incantation]], [[reference_flaky_replaceall_emission_tests]].
