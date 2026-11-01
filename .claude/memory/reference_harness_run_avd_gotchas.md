---
name: reference_harness_run_avd_gotchas
description: "Why `make harness-test` hangs here and how to run the harness suites pinned to a self-booted AVD"
metadata: 
  node_type: memory
  type: reference
  originSessionId: e27f97b2-17c6-42e3-a821-13bd86117c4d
---

`make harness-test` can hang forever in this environment: the Makefile's `PHONE_AVD := Harness_Medium_Phone`, but the machine only has **`Harness_Medium_Phone_2`** (API 25). The emulator process dies instantly ("Unknown AVD name") while the macro's `until [ -n "$SERIAL" ]` boot-wait loop spins indefinitely. `TABLET_AVD := Harness_Medium_Tablet` DOES exist.

Run the harness subsets manually, pinned via `ANDROID_SERIAL` (same pattern as [[reference_harness_macro_only_app_module]]):
- Phone: boot `Harness_Medium_Phone_2` (or any medium-phone AVD), then
  `ANDROID_SERIAL=emulator-XXXX ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.riffle.app.harness.TabletLayout`
- Tablet: boot `Harness_Medium_Tablet`, then same with `...arguments.annotation=com.riffle.app.harness.TabletLayout`
- Single class: `...arguments.class=<fqcn>` (comma-separate multiple).

**A freshly-booted emulator MUST be woken and unlocked first**, or Compose touch tests fail with `AssertionError: Failed to inject touch input` (seen across both API-25 and API-33, e.g. WakeLockHarnessTest, PdfHarnessTest):
`adb -s emulator-XXXX shell input keyevent KEYCODE_WAKEUP; adb ... shell wm dismiss-keyguard; adb ... shell svc power stayon true`

WebView column-measurement tests (NarratedColumnsJsTest, EpubCfiTranslatorInstrumentedTest) FAIL on a fresh API-33 AVD (different WebView) but PASS on the canonical API-25 `Harness_Medium_Phone_2` — run harness on API 25, matching [[reference_test_avd_chrome55_webview]]. Full phone subset on API-25 = 211 tests / 5 skipped / 0 failed.

Per-AVD heap: the Makefile bumps `vm.heapSize=1024` in `~/.android/avd/<name>.avd/config.ini` before booting; do the same. Boot flags: `-no-window -no-audio -no-boot-anim -no-snapshot-load -port NNNN`.
