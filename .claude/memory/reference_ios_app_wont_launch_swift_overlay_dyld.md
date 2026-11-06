---
name: ios-app-wont-launch-swift-overlay-dyld
description: iOS harness "does not have a process ID" / "background assertion pid 0" on every test = app aborts in dyld before main(); cause was a strongly linked Swift overlay (libswiftWebKit) absent from the iOS 18 runtime; how to diagnose in 25 min instead of 2 h
metadata:
  type: reference
---

Diagnosed 2026-10-11 on PR #1204 (branch pkmetski/next-multi-platform-1189). Eight consecutive iOS
`Harness tests (phone)` runs hit the 120-min timeout with ZERO test lines in the log while the same
suite passed locally (iOS 27) in 21 min and main passed on CI in 42 min.

**Symptom chain:** `-parallel-testing-enabled YES` buffers all per-test output until the suite ends,
so a timed-out run says nothing. Serial mode showed every test failing at `XCUIApplication.launch()`:
`Application 'com.riffle.iosApp' does not have a process ID`, `Failed to get background assertion for
target app with pid 0`, `Failed to get launch progress`. Each test burnt 60–420 s × 2 iterations.

**Root cause:** `ElidedPdfBridge.swift` called `WKWebView.pdf(configuration:)` and
`WKPDFConfiguration.rect` — Swift *overlay* APIs (mangled `…C6WebKitE…`). Xcode links
`/usr/lib/swift/libswiftWebKit.dylib` strongly; iOS 18.x runtimes do not ship it → dyld aborts before
`main()`. iOS 26+/27 ships it, so local runs and the Xcode 27 build were green. Hosted unit tests on
CI also passed because they compile app sources into the test bundle, not the app's dyld closure.
Fix: `webView.__createPDF(with:completionHandler:)` (NS_REFINED_FOR_SWIFT import of the ObjC method)
and no `rect` assignment. Verify with `otool -L Riffle.debug.dylib | grep libswift` (no libswiftWebKit)
and `nm -u … | grep 6WebKitE` (empty).

**How to apply:** when an iOS harness run is silent/timing out, do NOT read Kotlin diffs for hours.
(1) Download `ios-harness-log`; zero `Test case` lines + build finished = launch/infra, not tests.
(2) The harness step now does `simctl launch --console-pty` of Riffle.app with `--RIFFLE_RESET_FOR_TESTS`
before the suite and checks every strongly linked `/usr/lib/swift/*.dylib` against the selected
runtime root (`check_swift_overlays` in ios.yml) — read `smoke-launch.log` first. (3) Locally,
`xcrun simctl launch --console-pty <udid> com.riffle.iosApp --RIFFLE_RESET_FOR_TESTS` prints dyld
errors directly. Local Xcode 27 cannot install iOS 18.x runtimes (`-downloadPlatform iOS
-buildVersion 18.5` → "not available"), so CI is the only iOS 18 check.
Related: [[ios-harness-flakiness-root-causes]], [[ios-ci-harness-slow-single-clone]].
