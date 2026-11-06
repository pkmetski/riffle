---
name: reference-harness-macro-only-app-module
description: make harness-test* only runs :app:connectedDebugAndroidTest — core:* androidTests (MigrationTest) are NOT covered; run them pinned to a self-booted Harness AVD
metadata: 
  node_type: memory
  type: reference
  originSessionId: b7c0a44d-5ce0-4617-ad02-a78d1deb4624
---

The `run_harness_tests` macro in the Makefile invokes **only** `./gradlew :app:connectedDebugAndroidTest` (with an `ANDROID_SERIAL` pinned to the booted Harness AVD and an annotation/class filter). It does **not** run any other module's androidTest.

Consequence: `core/database/src/androidTest/.../MigrationTest.kt` (and any other `core:*` instrumented test) is **never executed by `make harness-test` / `make harness-test-class`**. `harness-test-class CLASS=...MigrationTest` boots the AVD but the `class=` filter just narrows the `:app` instrumentation, finds nothing matching, and the run is meaningless for migration tests.

To actually run the migration tests, boot the Harness AVD yourself and pin the module's connected task to its serial (honours [[feedback_never_touch_other_emulators]] — only your serial):

```bash
# confirm none of the already-running emulators is Harness_Medium_Phone, then:
emulator -avd Harness_Medium_Phone -no-window -no-audio -no-boot-anim -no-snapshot-load &
# find the NEW serial that maps to Harness_Medium_Phone, wait for sys.boot_completed=1, then:
ANDROID_SERIAL=emulator-555X ./gradlew :core:database:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.riffle.core.database.MigrationTest
```

CLAUDE.md forbids bare `:app:connectedDebugAndroidTest` (hits all devices); pinning `ANDROID_SERIAL` to your own booted AVD is the safe equivalent for `:core:database`. See [[reference_migration_tests_api25_preexisting_fails]] for the known pre-existing failures to not chase.
