# Logger Seam Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ~20 scattered `Log.[dweiv]("RIFFLE_*", …)` literals across 7 files with a typed `Logger` seam keyed by a `LogChannel` enum, gated by a CI grep task.

**Architecture:** New `core/logging` Android library exposes `LogChannel` (enum) + `Logger` (interface) + `AndroidLogger` (production impl over `android.util.Log`) + `RecordingLogger` (test impl). Hilt binds `Logger → AndroidLogger`. All 7 production sites switch to constructor-injected `Logger`. A `checkRiffleLogTags` gradle task wired into `check` fails the build if `Log\.[dweiv]\("RIFFLE_` reappears outside `core/logging`.

**Tech Stack:** Kotlin, AGP android-library, Hilt, JUnit4, gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-06-30-logger-seam-design.md`

**Issue:** #337 — branch `pkmetski/logger-seam`.

## Global Constraints

- New module path: `core/logging`. Namespace: `com.riffle.core.logging`. Mirrors `core/data` build script (compileSdk 37, minSdk 24, JVM target 17, core-library desugaring, Hilt + KSP).
- Seed channels: only `Readaloud("RIFFLE_RA")`, `Audiobook("RIFFLE_AB")`, `Handoff("RIFFLE_HANDOFF")`. No aspirational channels.
- All migrated sites use constructor-injected `Logger` — no `companion object { const val LOG = "RIFFLE_*" }` may remain.
- `RecordingLogger` lives in `src/main` (not `src/test`) so consumers can import it from their own test source sets without test-fixture plumbing.
- After the sweep, the only file in the repo containing `"RIFFLE_RA"`, `"RIFFLE_AB"`, or `"RIFFLE_HANDOFF"` string literals must be `core/logging/.../LogChannel.kt`. `androidTest` keeps its `"RIFFLE_TEST"` literal — that one is out of scope.
- Each task ends with `./gradlew test` (the relevant modules) green, then a commit. Follow Riffle's conventional-commit style (`refactor(infra): …`, `chore(infra): …`).
- Do not push or open a PR during the plan — finalize is a separate step the user runs.

---

### Task 1: Create `core/logging` module skeleton + `LogChannel` + `Logger` interface

Stand the module up with the enum and the interface only. No impls yet. A JVM unit test pins the channel-to-tag mapping so a future typo can't silently break the `adb logcat` recipes.

**Files:**
- Create: `core/logging/build.gradle.kts`
- Create: `core/logging/src/main/AndroidManifest.xml`
- Create: `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt`
- Create: `core/logging/src/main/kotlin/com/riffle/core/logging/Logger.kt`
- Create: `core/logging/src/test/kotlin/com/riffle/core/logging/LogChannelTest.kt`
- Modify: `settings.gradle.kts` (add `include(":core:logging")`)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class LogChannel(val tag: String) { Readaloud("RIFFLE_RA"), Audiobook("RIFFLE_AB"), Handoff("RIFFLE_HANDOFF") }`
  - `interface Logger { fun d(channel: LogChannel, t: Throwable? = null, msg: () -> String); fun w(channel: LogChannel, t: Throwable? = null, msg: () -> String); fun e(channel: LogChannel, t: Throwable? = null, msg: () -> String) }`

- [ ] **Step 1: Add the module to settings**

Append to `settings.gradle.kts`:

```kotlin
include(":core:logging")
```

- [ ] **Step 2: Create the module build script**

Write `core/logging/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.riffle.core.logging"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
```

- [ ] **Step 3: Create the empty manifest**

Write `core/logging/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Write the failing channel-tag test**

Write `core/logging/src/test/kotlin/com/riffle/core/logging/LogChannelTest.kt`:

```kotlin
package com.riffle.core.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class LogChannelTest {
    @Test
    fun tagsMatchTheDebugRecipes() {
        // Stable wire-format: these tag strings are referenced by adb logcat recipes in AGENTS.md
        // and the debug skills. Changing them silently breaks team debugging.
        assertEquals("RIFFLE_RA", LogChannel.Readaloud.tag)
        assertEquals("RIFFLE_AB", LogChannel.Audiobook.tag)
        assertEquals("RIFFLE_HANDOFF", LogChannel.Handoff.tag)
    }
}
```

- [ ] **Step 5: Run the test, verify it fails**

Run:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:logging:testDebugUnitTest
```

Expected: compile failure — `LogChannel` does not exist yet.

- [ ] **Step 6: Implement `LogChannel`**

Write `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt`:

```kotlin
package com.riffle.core.logging

/**
 * Stable log-tag namespace for Riffle's production debug recipes
 * (`adb logcat -d | grep RIFFLE_*`).
 *
 * The string values are part of the contract with AGENTS.md and the debug skills — never
 * rename a tag without updating both places. Add a new channel by adding an enum entry; do
 * not introduce `Log.d("RIFFLE_*", …)` literals elsewhere (enforced by `checkRiffleLogTags`).
 */
enum class LogChannel(val tag: String) {
    Readaloud("RIFFLE_RA"),
    Audiobook("RIFFLE_AB"),
    Handoff("RIFFLE_HANDOFF"),
}
```

- [ ] **Step 7: Implement `Logger`**

Write `core/logging/src/main/kotlin/com/riffle/core/logging/Logger.kt`:

```kotlin
package com.riffle.core.logging

/**
 * Typed logging seam. Inject [Logger] (production) or substitute [RecordingLogger] in tests.
 *
 * The [msg] argument is a lambda so a future channel gate is zero-cost on the hot path
 * (no string interpolation when the channel is disabled). Today every call evaluates it.
 *
 * Pass [t] when logging an exception — implementations forward it to the underlying logger.
 */
interface Logger {
    fun d(channel: LogChannel, t: Throwable? = null, msg: () -> String)
    fun w(channel: LogChannel, t: Throwable? = null, msg: () -> String)
    fun e(channel: LogChannel, t: Throwable? = null, msg: () -> String)
}
```

- [ ] **Step 8: Run the test, verify it passes**

Run:

```bash
./gradlew :core:logging:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts core/logging
git commit -m "feat(logging): add core/logging module with LogChannel and Logger interface (#337)"
```

---

### Task 2: `RecordingLogger` test impl

Add the in-memory `RecordingLogger` so tests can assert on log emissions. It lives in `src/main` so app tests can import it without depending on `core/logging`'s test source set.

**Files:**
- Create: `core/logging/src/main/kotlin/com/riffle/core/logging/RecordingLogger.kt`
- Create: `core/logging/src/test/kotlin/com/riffle/core/logging/RecordingLoggerTest.kt`

**Interfaces:**
- Consumes: `Logger`, `LogChannel` (Task 1).
- Produces:
  - `class RecordingLogger : Logger`
  - `data class RecordingLogger.Record(val level: Level, val channel: LogChannel, val message: String, val throwable: Throwable?)`
  - `enum class RecordingLogger.Level { D, W, E }`
  - `RecordingLogger.records: List<Record>` — snapshot getter.
  - `fun RecordingLogger.records(channel: LogChannel): List<Record>`
  - `fun RecordingLogger.clear()`

- [ ] **Step 1: Write the failing test**

Write `core/logging/src/test/kotlin/com/riffle/core/logging/RecordingLoggerTest.kt`:

```kotlin
package com.riffle.core.logging

import com.riffle.core.logging.RecordingLogger.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLoggerTest {

    @Test
    fun recordsLevelChannelAndMessage() {
        val logger = RecordingLogger()
        logger.d(LogChannel.Readaloud) { "hello d" }
        logger.w(LogChannel.Audiobook) { "hello w" }
        logger.e(LogChannel.Handoff) { "hello e" }

        val all = logger.records
        assertEquals(3, all.size)
        assertEquals(Level.D, all[0].level)
        assertEquals(LogChannel.Readaloud, all[0].channel)
        assertEquals("hello d", all[0].message)
        assertNull(all[0].throwable)
        assertEquals(Level.W, all[1].level)
        assertEquals(LogChannel.Audiobook, all[1].channel)
        assertEquals(Level.E, all[2].level)
        assertEquals(LogChannel.Handoff, all[2].channel)
    }

    @Test
    fun throwableIsCapturedWhenPassed() {
        val logger = RecordingLogger()
        val boom = IllegalStateException("boom")
        logger.e(LogChannel.Readaloud, boom) { "blew up" }

        val record = logger.records.single()
        assertSame(boom, record.throwable)
    }

    @Test
    fun recordsByChannelFilters() {
        val logger = RecordingLogger()
        logger.d(LogChannel.Readaloud) { "a" }
        logger.d(LogChannel.Audiobook) { "b" }
        logger.d(LogChannel.Readaloud) { "c" }

        val ra = logger.records(LogChannel.Readaloud)
        assertEquals(listOf("a", "c"), ra.map { it.message })
    }

    @Test
    fun clearEmptiesRecords() {
        val logger = RecordingLogger()
        logger.d(LogChannel.Readaloud) { "x" }
        logger.clear()
        assertTrue(logger.records.isEmpty())
    }

    @Test
    fun msgLambdaIsEvaluatedExactlyOncePerCall() {
        val logger = RecordingLogger()
        var calls = 0
        logger.d(LogChannel.Readaloud) {
            calls++
            "msg #$calls"
        }
        assertEquals(1, calls)
        assertEquals("msg #1", logger.records.single().message)
    }
}
```

- [ ] **Step 2: Run, verify failure**

Run:

```bash
./gradlew :core:logging:testDebugUnitTest
```

Expected: compile failure — `RecordingLogger` does not exist.

- [ ] **Step 3: Implement `RecordingLogger`**

Write `core/logging/src/main/kotlin/com/riffle/core/logging/RecordingLogger.kt`:

```kotlin
package com.riffle.core.logging

/**
 * In-memory [Logger] for tests. Captures every emission so tests can assert on level, channel,
 * message, and (optional) throwable.
 *
 * NOT thread-safe. Tests that emit from multiple threads or coroutines must synchronise
 * externally or convert to a snapshot before asserting.
 */
class RecordingLogger : Logger {

    enum class Level { D, W, E }

    data class Record(
        val level: Level,
        val channel: LogChannel,
        val message: String,
        val throwable: Throwable?,
    )

    private val mutable = mutableListOf<Record>()

    val records: List<Record>
        get() = mutable.toList()

    fun records(channel: LogChannel): List<Record> = mutable.filter { it.channel == channel }

    fun clear() {
        mutable.clear()
    }

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) {
        mutable += Record(Level.D, channel, msg(), t)
    }

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) {
        mutable += Record(Level.W, channel, msg(), t)
    }

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) {
        mutable += Record(Level.E, channel, msg(), t)
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run:

```bash
./gradlew :core:logging:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add core/logging/src
git commit -m "feat(logging): add RecordingLogger test impl (#337)"
```

---

### Task 3: `AndroidLogger` + Hilt binding

Add the production impl that forwards to `android.util.Log` and a Hilt module that binds `Logger` → `AndroidLogger` in `SingletonComponent`.

**Files:**
- Create: `core/logging/src/main/kotlin/com/riffle/core/logging/AndroidLogger.kt`
- Create: `core/logging/src/main/kotlin/com/riffle/core/logging/LoggingModule.kt`

**Interfaces:**
- Consumes: `Logger`, `LogChannel` (Task 1).
- Produces:
  - `class AndroidLogger @Inject constructor() : Logger` — bound as `Singleton`.
  - `object LoggingModule` providing `Logger`.

- [ ] **Step 1: Implement `AndroidLogger`**

Write `core/logging/src/main/kotlin/com/riffle/core/logging/AndroidLogger.kt`:

```kotlin
package com.riffle.core.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [Logger]: forwards to [android.util.Log] using the channel's tag.
 *
 * No release-build gating today — every call hits `Log.*`. The lambda message arg keeps the
 * door open for a future `if (channel !in enabledChannels) return` short-circuit.
 */
@Singleton
class AndroidLogger @Inject constructor() : Logger {

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.d(channel.tag, msg(), t) else Log.d(channel.tag, msg())
    }

    override fun w(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.w(channel.tag, msg(), t) else Log.w(channel.tag, msg())
    }

    override fun e(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.e(channel.tag, msg(), t) else Log.e(channel.tag, msg())
    }
}
```

- [ ] **Step 2: Implement the Hilt binding**

Write `core/logging/src/main/kotlin/com/riffle/core/logging/LoggingModule.kt`:

```kotlin
package com.riffle.core.logging

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {

    @Binds
    @Singleton
    abstract fun bindLogger(impl: AndroidLogger): Logger
}
```

- [ ] **Step 3: Compile the module**

Run:

```bash
./gradlew :core:logging:assembleDebug :core:logging:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 6 tests still passing.

- [ ] **Step 4: Wire the module dependency into `:app`**

Inspect `app/build.gradle.kts` and add to the `dependencies { }` block alongside the other `project(":core:*")` lines:

```kotlin
implementation(project(":core:logging"))
```

Run:

```bash
./gradlew :app:assembleDebug -x test
```

Expected: `BUILD SUCCESSFUL` (the binding is set up; nothing injects it yet).

- [ ] **Step 5: Commit**

```bash
git add core/logging/src app/build.gradle.kts
git commit -m "feat(logging): add AndroidLogger + Hilt binding (#337)"
```

---

### Task 4: Migrate `ReadaloudController`

`ReadaloudController` owns the `RIFFLE_HANDOFF` constant that `AudiobookController` re-imports. Migrate this file first so Task 5 can drop the cross-class import.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt` (8 `Log.*` sites at lines 81, 91, 97, 103, 116, 144, 182, 225 in pre-change file; companion constants at lines 319–320)

**Interfaces:**
- Consumes: `Logger`, `LogChannel` from Task 1; Hilt binding from Task 3.
- Produces: a `@Singleton` class whose primary constructor includes `logger: Logger` after the existing `@ApplicationContext context: Context?` and `applicationScope: ApplicationScope?` params, with a corresponding zero-arg test seam.

- [ ] **Step 1: Edit the constructor and protected test seam**

Open `ReadaloudController.kt`. Replace the constructor and protected `()` constructor:

Before:

```kotlin
@Singleton
open class ReadaloudController @Inject constructor(
    @ApplicationContext private val context: Context?,
    applicationScope: ApplicationScope?,
) {
    // Test seam: subclasses that override the pre-warm methods need no real Context (only consulted in
    // [ensureConnected], which fakes never reach). Keeps the controller unit-fakeable without Robolectric.
    protected constructor() : this(null, null)
```

After:

```kotlin
@Singleton
open class ReadaloudController @Inject constructor(
    @ApplicationContext private val context: Context?,
    applicationScope: ApplicationScope?,
    private val logger: Logger,
) {
    // Test seam: subclasses that override the pre-warm methods need no real Context (only consulted in
    // [ensureConnected], which fakes never reach). Keeps the controller unit-fakeable without Robolectric.
    protected constructor() : this(null, null, RecordingLogger())
```

Add to the import block (alphabetically near the other `com.riffle.core` imports):

```kotlin
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.RecordingLogger
```

Remove the import:

```kotlin
import android.util.Log
```

- [ ] **Step 2: Rewrite each `Log.*` call**

Locate the 8 sites with `grep -n 'Log\.' app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt` and rewrite each. Map:

| Before                                                                                          | After                                                                                                                  |
|-------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `Log.d(HANDOFF, "RA.onPlaybackStateChanged state=…")`                                           | `logger.d(LogChannel.Handoff) { "RA.onPlaybackStateChanged state=…" }`                                                 |
| `Log.e(LOG, "playback error code=… src=…", error)`                                              | `logger.e(LogChannel.Readaloud, error) { "playback error code=… src=…" }`                                              |
| `Log.d(HANDOFF, "RA.prepare start (controller already connected=${controller != null})")`      | `logger.d(LogChannel.Handoff) { "RA.prepare start (controller already connected=${controller != null})" }`             |
| `Log.d(HANDOFF, "RA.prepare ensureConnected +…ms")`                                             | `logger.d(LogChannel.Handoff) { "RA.prepare ensureConnected +…ms" }`                                                   |
| `Log.d(HANDOFF, "RA.prepare setMediaItems+prepare +…ms")`                                       | `logger.d(LogChannel.Handoff) { "RA.prepare setMediaItems+prepare +…ms" }`                                             |
| `Log.d(HANDOFF, "RA.play called")`                                                              | `logger.d(LogChannel.Handoff) { "RA.play called" }`                                                                    |
| `Log.d(HANDOFF, "RA.releaseForHandoff (T0 — audio pausing)")`                                   | `logger.d(LogChannel.Handoff) { "RA.releaseForHandoff (T0 — audio pausing)" }`                                         |
| `Log.d(HANDOFF, "RA.playFromSecond (preWarmed=${preWarmedPosition != null})")`                  | `logger.d(LogChannel.Handoff) { "RA.playFromSecond (preWarmed=${preWarmedPosition != null})" }`                        |

Preserve every string verbatim — including the em-dash and emoji.

- [ ] **Step 3: Delete the companion-object constants**

In the `companion object` near the bottom of the file, delete these two lines (and the empty line above them if it leaves a stray blank):

```kotlin
        private const val LOG = "RIFFLE_RA"
        internal const val HANDOFF = "RIFFLE_HANDOFF"
```

Note: `AudiobookController` imports `HANDOFF` from this companion. Removing it now intentionally breaks `AudiobookController`'s compile — Task 5 fixes that.

- [ ] **Step 4: Verify no `RIFFLE_*` or `Log.*` literals remain**

Run:

```bash
grep -nE 'Log\.|RIFFLE_' app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt
```

Expected: no output.

- [ ] **Step 5: Run the controller's unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '*ReadaloudController*'
```

Expected: existing unit tests still pass. The `:app` module won't yet compile because of Task 5's pending edit, but the tests that exercise `ReadaloudController` directly compile via the `protected constructor()` test seam — if the suite fails to compile due to the missing `HANDOFF` import in `AudiobookController`, that's expected; move straight to Task 5 and commit after Step 5 there.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt
git commit -m "refactor(logging): migrate ReadaloudController to Logger seam (#337)"
```

---

### Task 5: Migrate `AudiobookController`

Drop the `ReadaloudController.Companion.HANDOFF` import (Task 4 deleted it), inject `Logger`, and rewrite the 6 call sites.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt` (lines 101, 111, 127, 139, 152, 304; companion const at 369)

**Interfaces:**
- Consumes: `Logger`, `LogChannel`.
- Produces: same class signature, plus a `Logger` constructor parameter.

- [ ] **Step 1: Edit the constructor**

Read the file's constructor block (around line 50–80) and add `private val logger: Logger` as the last constructor parameter, alongside whatever existing `@Inject constructor(...)` parameters it has. If there is a `protected constructor(): this(...)` test seam (analogous to `ReadaloudController`), pass `RecordingLogger()` for the new arg there too. If there isn't a test seam, do not add one — keep the diff minimal.

- [ ] **Step 2: Fix the imports**

Remove:

```kotlin
import android.util.Log
import com.riffle.app.feature.reader.readaloud.ReadaloudController.Companion.HANDOFF
```

Add (alphabetically among `com.riffle.core.*`):

```kotlin
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
```

Plus `import com.riffle.core.logging.RecordingLogger` only if you added a test seam in Step 1.

- [ ] **Step 3: Rewrite the 6 call sites**

Mapping:

| Before                                                                                  | After                                                                                                       |
|-----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `Log.d(HANDOFF, "AB.onPlaybackStateChanged state=…")`                                  | `logger.d(LogChannel.Handoff) { "AB.onPlaybackStateChanged state=…" }`                                      |
| `Log.e(LOG, "playback error code=… src=…", error)`                                     | `logger.e(LogChannel.Audiobook, error) { "playback error code=… src=…" }`                                   |
| `Log.d(HANDOFF, "AB.prepare start (controller already connected=…)")`                  | `logger.d(LogChannel.Handoff) { "AB.prepare start (controller already connected=…)" }`                      |
| `Log.d(HANDOFF, "AB.prepare ensureConnected +…ms")`                                    | `logger.d(LogChannel.Handoff) { "AB.prepare ensureConnected +…ms" }`                                        |
| `Log.d(HANDOFF, "AB.prepare setMediaItems+prepare +…ms")`                              | `logger.d(LogChannel.Handoff) { "AB.prepare setMediaItems+prepare +…ms" }`                                  |
| `Log.d(HANDOFF, "AB.releaseForHandoff (T0 — audio pausing)")`                          | `logger.d(LogChannel.Handoff) { "AB.releaseForHandoff (T0 — audio pausing)" }`                              |

Preserve every string verbatim.

- [ ] **Step 4: Delete the companion-object constant**

In the `companion object`, delete:

```kotlin
        private const val LOG = "RIFFLE_AB"
```

- [ ] **Step 5: Verify file is clean**

Run:

```bash
grep -nE 'Log\.|RIFFLE_|HANDOFF' app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt
```

Expected: no output.

- [ ] **Step 6: Build `:app` to confirm both controllers compile together**

Run:

```bash
./gradlew :app:assembleDebug -x test
```

Expected: `BUILD SUCCESSFUL`. If a Hilt error appears about `RecordingLogger` not having an `@Inject` constructor inside `AudiobookController`'s protected seam, that means you accidentally wired `RecordingLogger()` through `@Inject` — only the `protected constructor()` seam should reference `RecordingLogger`; the `@Inject` primary constructor receives `Logger`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt
git commit -m "refactor(logging): migrate AudiobookController to Logger seam (#337)"
```

---

### Task 6: Migrate the remaining 5 files

These are small: 1–2 sites each, three of them already use fully-qualified `android.util.Log.*(literal, ...)`. Sweep them in one commit so the `RIFFLE_*` literals are gone from production code.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ZipAudioDataSource.kt` (2 sites, lines 39 & 44; companion const at 95)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt` (1 site, helper at lines 1057–1059)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` (1 site, line 550)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/ReaderSyncFactory.kt` (1 site, lines 73–74)
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/session/ReadaloudSession.kt` (1 site, line 1017)

**Interfaces:**
- Consumes: `Logger`, `LogChannel`.
- Produces: each class accepts `logger: Logger` via its existing constructor injection pattern. For non-Hilt classes that are constructed directly, route `Logger` through the call chain — see per-file notes below.

- [ ] **Step 1: `ZipAudioDataSource` — add Logger via constructor**

Read the file. It's a Media3 `BaseDataSource`. Find its constructor (likely `class ZipAudioDataSource(...) : BaseDataSource(...)`). Add `private val logger: Logger` as the last constructor parameter. Find every call site that constructs `ZipAudioDataSource(...)` (`grep -rn 'ZipAudioDataSource(' app/src/main`) and thread a `Logger` through — usually that call site is in a factory that itself has a `Logger` after Tasks 4–5, or you'll need to add one. If the constructor is invoked from a non-injected location, prefer adding a `@Inject Logger` field to the surrounding `@Singleton`/`@Inject`-class and passing it down.

Replace:

```kotlin
Log.e(LOG, "open: ZipFile(${bundle.name} size=${bundle.length()}) FAILED for $entryPath", e)
```

with:

```kotlin
logger.e(LogChannel.Readaloud, e) { "open: ZipFile(${bundle.name} size=${bundle.length()}) FAILED for $entryPath" }
```

Replace:

```kotlin
Log.e(LOG, "open: MISSING entry '$entryPath' in ${bundle.name} (size=${bundle.length()}); likely a truncated bundle")
```

with:

```kotlin
logger.e(LogChannel.Readaloud) { "open: MISSING entry '$entryPath' in ${bundle.name} (size=${bundle.length()}); likely a truncated bundle" }
```

Delete:

```kotlin
        private const val LOG = "RIFFLE_RA"
```

Remove `import android.util.Log`, add the two `com.riffle.core.logging.*` imports.

- [ ] **Step 2: `AudiobookPlayerViewModel` — replace the try/catch helper**

The current helper at lines 1057–1059 is:

```kotlin
        private const val HANDOFF = "RIFFLE_HANDOFF"

        fun log(msg: String) = try { Log.d(HANDOFF, msg) } catch (_: Throwable) { }
```

This helper exists as a static companion. Replace the companion's `log(msg)` helper with a direct `logger.d(LogChannel.Handoff)` call at each call site. Steps:

1. `grep -n '\blog(' app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt` — find every call to the helper.
2. For each one, rewrite `log("msg")` → `logger.d(LogChannel.Handoff) { "msg" }`. The ViewModel is `@HiltViewModel @Inject constructor(...)`; add `private val logger: Logger` to its constructor parameters.
3. Delete the entire companion-object block (`private const val HANDOFF` + `fun log(msg)`) if those are its only members; otherwise just delete the two lines and the now-unused `import android.util.Log`.

The original try/catch existed because the helper was called from companion-static contexts where `android.util.Log` might (in theory) have thrown during tests. With constructor-injected `Logger`, tests use `RecordingLogger` which never throws — the try/catch is no longer needed.

- [ ] **Step 3: `EpubReaderViewModel` — single fully-qualified site**

Add `private val logger: Logger` to the constructor (the ViewModel is `@HiltViewModel @Inject constructor(...)`).

Replace at line 550:

```kotlin
android.util.Log.d("RIFFLE_HANDOFF", "RA.audiobookItemId resolved=$resolvedAudiobookItemId (overlay can now mount)")
```

with:

```kotlin
logger.d(LogChannel.Handoff) { "RA.audiobookItemId resolved=$resolvedAudiobookItemId (overlay can now mount)" }
```

Add the imports.

- [ ] **Step 4: `ReaderSyncFactory` — single site**

Read the file to see how it's constructed (factory class). Add `private val logger: Logger` to its primary constructor; thread it through wherever `ReaderSyncFactory` is instantiated (probably injected via Hilt — confirm with `grep -rn 'ReaderSyncFactory(' app/src/main`).

Replace lines 73–74:

```kotlin
android.util.Log.w(
    "RIFFLE_RA",
    …
)
```

with:

```kotlin
logger.w(LogChannel.Readaloud) {
    …
}
```

Add the imports.

- [ ] **Step 5: `ReadaloudSession` — single site**

Read the file's constructor. Add `private val logger: Logger`; thread it through call sites if not injected.

Replace at line 1017:

```kotlin
android.util.Log.e("RIFFLE_RA", "buildSentenceQuotes failed", e)
```

with:

```kotlin
logger.e(LogChannel.Readaloud, e) { "buildSentenceQuotes failed" }
```

Add the imports.

- [ ] **Step 6: Verify the sweep is complete**

Run:

```bash
grep -rnE 'Log\.[dweiv]\("RIFFLE_|"RIFFLE_(RA|AB|HANDOFF)"' app/src/main core/data/src/main core/network/src/main core/database/src/main core/domain/src/main 2>/dev/null
```

Expected: no output. Every `RIFFLE_*` literal now lives only inside `core/logging/.../LogChannel.kt`.

- [ ] **Step 7: Build + run JVM tests**

Run:

```bash
./gradlew :app:assembleDebug -x test
./gradlew test
```

Expected: `BUILD SUCCESSFUL` from assembleDebug; full JVM suite green. If any pre-existing flake fires (see CLAUDE.md memories on `flaky replaceAllForLibrary emission tests` and `autoFollowJsTest paginated-snap pre-existing fail`), confirm it's the known flake and not a regression by re-running just the affected test class.

- [ ] **Step 8: Commit**

```bash
git add app/src/main
git commit -m "refactor(logging): sweep remaining RIFFLE_* sites to Logger seam (#337)"
```

---

### Task 7: Add a `RecordingLogger`-backed assertion to one existing test

Prove the test seam works end-to-end by amending one existing readaloud or handoff test to assert via `RecordingLogger`. Demonstrates the pattern for future tests.

**Files:**
- Modify: one existing test in `app/src/test/kotlin/com/riffle/app/feature/reader/readaloud/` (whichever class already exercises `ReadaloudController` or `AudiobookPlayerViewModel` with a fake/test seam)

**Interfaces:**
- Consumes: `RecordingLogger`, `LogChannel`.
- Produces: one new `@Test` method (or an addition to an existing one) asserting that a specific behaviour emits the expected `HANDOFF` log.

- [ ] **Step 1: Find a candidate test**

Run:

```bash
grep -rln 'ReadaloudController\|AudiobookController' app/src/test
```

Pick the smallest test class that already constructs a `ReadaloudController` (or subclass) and exercises the `prepare`/`play` path that emits `HANDOFF` logs. If none exists, target `app/src/test/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModelTest.kt` (or analogous) — the ViewModel from Task 6 Step 2 now takes `Logger` in its constructor.

- [ ] **Step 2: Add a `RecordingLogger`-backed assertion**

In the chosen test, instantiate `val logger = RecordingLogger()`, thread it into the class under test, exercise the code path that emits a `HANDOFF` log, then assert. Example shape:

```kotlin
@Test
fun prepareEmitsHandoffStartLog() {
    val logger = RecordingLogger()
    val controller = TestableReadaloudController(logger = logger)   // adapt to whatever the existing test seam looks like
    controller.prepare(/* args */)

    val messages = logger.records(LogChannel.Handoff).map { it.message }
    assertTrue(
        "expected an 'RA.prepare start' message but got $messages",
        messages.any { it.startsWith("RA.prepare start") },
    )
}
```

Use whatever existing test seam (`TestReadaloudController` / `FakeReadaloudController`) is already in the file. The point is to demonstrate the `RecordingLogger` pattern, not to refactor the test.

- [ ] **Step 3: Run the test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests '<fully.qualified.TestClass>'
```

Expected: green.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/kotlin
git commit -m "test(logging): demonstrate RecordingLogger pattern in <Class>Test (#337)"
```

---

### Task 8: `checkRiffleLogTags` gradle task

Add a root-project gradle task that scans for `Log\.[dweiv]\("RIFFLE_` literals outside `core/logging/src/main` and fails the build. Wire it into the standard `check` task so CI runs it automatically.

**Files:**
- Modify: `build.gradle.kts` (root)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `./gradlew checkRiffleLogTags` (red on a forbidden literal, green otherwise)
  - `./gradlew check` depends on it transitively.

- [ ] **Step 1: Inspect the root build script**

Read `build.gradle.kts` at the repo root to see its current structure (likely just `plugins { }` aliases). The new task is registered at the bottom.

- [ ] **Step 2: Add the task**

Append to the root `build.gradle.kts`:

```kotlin
// Enforces that `Log.[dweiv]("RIFFLE_…"` literals only live in core/logging.
// Anything else: route the call through `Logger` + `LogChannel`. See #337.
val checkRiffleLogTags by tasks.registering {
    group = "verification"
    description = "Fails if any RIFFLE_* log-tag literal escapes core/logging."

    val forbidden = Regex("""Log\.[dweiv]\("RIFFLE_""")
    val allowedRoot = file("core/logging/src/main")
    val scanRoots = listOf(file("app/src"), file("core"))

    doLast {
        val offenders = mutableListOf<String>()
        scanRoots
            .filter { it.exists() }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.startsWith(allowedRoot.absolutePath) }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (forbidden.containsMatchIn(line)) {
                            offenders += "${f.relativeTo(rootDir)}:${idx + 1} — $line"
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "RIFFLE_* log-tag literals must live in core/logging/.../LogChannel.kt.\n" +
                    "Route these through Logger + LogChannel (see #337):\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

// Make it part of the normal `./gradlew check` run.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkRiffleLogTags"))
    }
}
```

(`androidTest` keeps `"RIFFLE_TEST"` — the regex targets only `RIFFLE_RA`/`AB`/`HANDOFF` patterns indirectly because those are the only callers of `Log.*("RIFFLE_`. If a future androidTest call uses `Log.d("RIFFLE_TEST", …)` it will also trip the regex; that is the intended behaviour — every `RIFFLE_*` tag belongs in the registry.)

- [ ] **Step 3: Run it against the swept tree**

Run:

```bash
./gradlew checkRiffleLogTags
```

Expected: `BUILD SUCCESSFUL` (Task 6 confirmed the tree is clean).

- [ ] **Step 4: Prove the rule bites — re-add a literal**

Temporarily edit `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt` and insert one stray line in any method:

```kotlin
        Log.d("RIFFLE_AB", "regression canary")
```

Run:

```bash
./gradlew checkRiffleLogTags
```

Expected: `BUILD FAILED` with an error message naming the file and line.

Revert the edit (`git checkout app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt`) and re-run `./gradlew checkRiffleLogTags` to confirm green again.

- [ ] **Step 5: Confirm the `check` wiring**

Run:

```bash
./gradlew :app:check --dry-run | grep -i RiffleLogTags
```

Expected: `:checkRiffleLogTags` appears in the dry-run output, demonstrating it is part of `:app:check`'s dependency graph.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "chore(logging): add checkRiffleLogTags lint task wired to check (#337)"
```

---

### Task 9: Document the channel registry in `AGENTS.md`

Add a short pointer so future agents and humans discover the registry on first read of `AGENTS.md`.

**Files:**
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: nothing.
- Produces: one new subsection under "Agent skills".

- [ ] **Step 1: Append the subsection**

Open `AGENTS.md`. Under the existing `## Agent skills` block (after the last triage/domain subsection), append:

```markdown
### Logger channels

Production log tags are typed in `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt` (`RIFFLE_RA`, `RIFFLE_AB`, `RIFFLE_HANDOFF`). Add a new channel by adding an enum entry; never introduce a new `Log.d("RIFFLE_*", …)` literal directly. Inject `Logger` (production: `AndroidLogger`; tests: `RecordingLogger`) and call `logger.d(LogChannel.X) { "msg" }`. The `checkRiffleLogTags` gradle task (wired into `check`) fails CI if a literal leaks back in.
```

- [ ] **Step 2: Run the docs through the gate**

Run:

```bash
./gradlew checkRiffleLogTags
```

Expected: green (docs aren't `.kt`, so they're not scanned; this is just a sanity re-run before commit).

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md
git commit -m "docs(agents): point at LogChannel registry (#337)"
```

---

### Task 10: Final verification

Confirm the whole branch is green and the spec's acceptance criteria are met.

- [ ] **Step 1: Run the full JVM suite**

Run:

```bash
./gradlew test
```

Expected: green. If a known flake fires (see CLAUDE.md memories: `flaky replaceAllForLibrary emission tests`, `autoFollowJsTest paginated-snap pre-existing fail`, `MigrationTest pre-existing fails`), re-run just that test class to confirm it's not a regression introduced by this branch.

- [ ] **Step 2: Run the harness smoke pass**

Run:

```bash
make harness-test
```

Expected: green (this refactor doesn't touch Readium / WebView / device code — instrumentation is just confirming we didn't break the build path).

- [ ] **Step 3: Spot-check the acceptance criteria from the spec**

Run each:

```bash
# 1. No RIFFLE_* literals outside core/logging:
grep -rnE 'Log\.[dweiv]\("RIFFLE_' app/src/main core/

# 2. No companion-object LOG/HANDOFF constants remain:
grep -rnE 'private const val (LOG|HANDOFF) = "RIFFLE_' app/src/main core/

# 3. Lint task green:
./gradlew checkRiffleLogTags

# 4. AGENTS.md pointer present:
grep -n 'Logger channels' AGENTS.md
```

Expected: items 1 & 2 produce no output; items 3 & 4 succeed.

- [ ] **Step 4: Stop**

Do NOT push, do NOT open the PR. Hand control back to the user — they run `/finalize` when ready.
