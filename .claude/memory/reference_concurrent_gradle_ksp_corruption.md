---
name: reference_concurrent_gradle_ksp_corruption
description: "Running ./gradlew test and ./gradlew lint concurrently corrupts app KSP caches (\"Storage corrupted\"/\"already registered\"); fix = clean kspCaches + --no-daemon"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 22582f64-ab51-466b-be1a-da3c8a3a3455
---

Kicking off `./gradlew test` and `./gradlew lint` **concurrently** (as the finalize skill suggests for parallelism) corrupts the shared `:app` KSP cache — both invocations run `:app:kspDebugKotlin` against `app/build/kspCaches` and stomp each other. Symptoms, in order seen:

- `e: [ksp] AssertionError: Storage corrupted .../kspCaches/debug/symbolLookups/lookups.tab_i`
- `e: [ksp] NoSuchFileException: .../generated/ksp/debug/java/.../EpubReaderViewModel_HiltModules.java`
- `e: [ksp] IllegalStateException: Storage for [.../symbolLookups/file-to-id.tab] is already registered`

The last one **persists across retries** even after `rm -rf app/build/kspCaches app/build/generated/ksp`, because a stale **gradle daemon** holds the KSP storage registered in its JVM memory. `--no-configuration-cache` alone does NOT fix it.

**Fix:** `rm -rf app/build/kspCaches app/build/generated/ksp` then run with **`--no-daemon`** (fresh single-use JVM, sidesteps the in-memory registration). Don't use `./gradlew --stop` — it kills daemons shared with sibling Conductor workspaces. Clean `--no-daemon` run then BUILD SUCCESSFUL.

**Avoid entirely:** run the JVM gradle suites (`test`, `lint`) **sequentially**, not concurrently. They are NOT independent — they share `:app` KSP build state. (Harness `make harness-*` targets also invoke `:app` ksp, so don't overlap them with a JVM gradle run either.) See [[reference_fresh_workspace_gradle_setup]].

Also note: piping gradle through `| tail -N` makes the background-task exit code reflect `tail` (always 0), masking `BUILD FAILED` — grep the log for FAILED, don't trust the notification's exit code.
