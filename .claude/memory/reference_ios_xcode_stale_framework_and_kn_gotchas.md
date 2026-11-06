---
name: ios-xcode-stale-framework-and-kn-gotchas
description: "iOS incremental builds ship the previous Kotlin (static K/N framework, no Ld edge) — rm the built .app/.xctest bundles before xcodebuild test (CI does since PR #1043); K/N NSLog varargs segfault; K/N string literals are UTF-16 in binaries"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 7cf17588-a5d2-4084-9231-bfe028665ad4
  modified: 2026-09-17T09:50:24.067Z
---

Three iOS gotchas (2026-09-07, updated 2026-09-17):

1. **Stale-framework incremental builds.** Riffle.framework is a *static* K/N framework. The
   KMP Embed Framework script phase relinks it via gradle and rsyncs it into `$(BUILT_PRODUCTS_DIR)`,
   declaring `$(BUILT_PRODUCTS_DIR)/Riffle.framework/Riffle` as output (commit `a03a3c725`).
   That is NOT sufficient: Xcode's Ld has no input edge to the framework, so a Kotlin-only change
   never relinks `Riffle.app/Riffle.debug.dylib` (Xcode 16 debug builds put the code in the
   debug dylib, `Riffle` is a stub executor). Confirmed 2026-09-17: dylib 09:02, framework 11:15,
   harness tests exercising code that no longer existed — locally AND on CI's cached DerivedData.
   Tried and REJECTED: (a) adding a BUILT_PRODUCTS_DIR `Riffle.framework` file reference to the
   Link phase — Swift recompiled (header changed) but Ld still skipped; (b) deleting the linked
   binaries from inside the script phase — breaks the stub-executor link ("no such file
   Riffle.debug.dylib"). WORKING mitigation (PR #1043, ios.yml): `rm -rf
   DerivedData/.../Build/Products/Debug-iphonesimulator/{Riffle.app,iosAppUnitTests.xctest,iosAppTests-Runner.app}`
   *before* `xcodebuild test` — a missing bundle makes Xcode rebuild/relink it. Do the same
   locally before trusting a harness result after a Kotlin edit. Verify with the log line
   `Ld .../Riffle.app/Riffle.debug.dylib`. Do NOT use `strings app | grep ClassName` as a
   freshness probe (see 3).

2. **K/N + NSLog varargs segfault**: `NSLog("%@", kotlinString)` crashes (EXC_BAD_ACCESS in
   respondsToSelector) — Kotlin/Native can't pass a Kotlin String through an ObjC variadic
   call. IosLogger uses println; IosLoggerTest pins it. Never reintroduce NSLog in Kotlin.

3. **K/N string literals are UTF-16 in binaries**: `strings` (ASCII) won't find Kotlin string
   constants in a dylib/framework — search for `text.encode('utf-16-le')` bytes instead.
   Kotlin class names show in the *framework* binary but not in the statically linked app binary.

Related: WKWebView test harness in [[AutoFollowJsTests]] must host webviews as fixed-frame
non-autoresizing subviews (root view gets resized to device screen → per-device layout),
set `contentInsetAdjustmentBehavior = .never`, and wait for document-height stability before
interacting (late content-size finalization resets scroll to 0). See also
[[ios-unsigned-sim-keychain-fails]] and [[compose-ios-ime-inset-clips-nonscrollable-forms]].
