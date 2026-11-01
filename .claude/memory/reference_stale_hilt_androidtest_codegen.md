---
name: reference-stale-hilt-androidtest-codegen
description: "app debugAndroidTest \"DaggerDefault_HiltComponents_SingletonC cannot find symbol\" = stale Hilt codegen in a fresh worktree; fix with rm -rf app/build/generated/hilt"
metadata: 
  node_type: memory
  type: reference
  originSessionId: b7c0a44d-5ce0-4617-ad02-a78d1deb4624
---

When the app's androidTest build fails with a wall of:

```
...HarnessTest_TestComponentDataSupplier.java: error: cannot find symbol
  import dagger.hilt.android.internal.testing.root.DaggerDefault_HiltComponents_SingletonC;
```

**This is almost always a REAL missing Hilt binding, not stale codegen** — `SingletonC` fails to *generate*, and every `*_TestComponentDataSupplier` that imports it then can't find the symbol. The cascade of "cannot find symbol" is a symptom; the root cause is one unsatisfied binding in the **test** graph.

**Prime suspect: `app/src/androidTest/.../di/TestDatabaseModule.kt`.** It is `@TestInstallIn(replaces = [DatabaseModule::class])` and hand-provides EVERY DAO. Add a new Room DAO + a `@HiltViewModel`/injectable that depends on it (e.g. via a new `*Store`) and the production `DatabaseModule` provider isn't enough — you MUST add the matching `provideXxxDao` to `TestDatabaseModule` too, or the test SingletonComponent won't build. (Learned the hard way adding `AudiobookPositionDao`: the prod `DatabaseModule` had it, the test module didn't → this exact error.)

Verify the real fix cleanly (don't trust a single javac pass — the config cache / stale generated sources can mask it):
```bash
rm -rf app/build/generated/hilt app/build/generated/ksp/debugAndroidTest
./gradlew :app:assembleDebugAndroidTest --rerun-tasks --no-configuration-cache
```

Only treat it as genuinely stale codegen if a clean `--rerun-tasks --no-configuration-cache` build passes with no source change. CI (`ci.yml`) does NOT build the app androidTest (only `assembleDebug` + `:core:database:connectedDebugAndroidTest`), so this break won't turn CI red — it only bites the local harness suite. See [[reference_harness_macro_only_app_module]].
