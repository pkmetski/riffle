---
name: kn-test-scheduler-background-scope
description: In kotlinx-coroutines-test, advanceUntilIdle() does NOT drain backgroundScope work — use testScheduler.runCurrent() when the code under test launches on a scope backed by backgroundScope (bit the iOS sweep-enqueuer tests in #1101)
metadata:
  type: reference
---

`TestScope.advanceUntilIdle()` only runs until **foreground** work is idle; coroutines launched on `backgroundScope` (e.g. a `DefaultApplicationScope(backgroundScope)` handed to production code) are left queued. The symptom is "expected 1, actual 0" with no error, on both JVM and iOS sim.

**How to apply:** pump with `testScheduler.runCurrent()` after each trigger (and after completing a `CompletableDeferred` gate). Pattern in `shared/src/iosTest/.../IosAnnotationSweepEnqueuerTest.kt`.

Related iOS-test gotchas from the same PR: a `core:data` iosTest that opens the real DB needs `-lsqlite3` on the test binaries (`iosSimulatorArm64 { binaries.all { linkerOpts("-lsqlite3") } }`, same as core:database/shared); clean up with `deleteRiffleDatabase(name)` (core:database iosMain). `parseWebDavBaseUrl` accepts any non-blank string (URLBuilder treats "not a url" as a path) — only blank yields null.
