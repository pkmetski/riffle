# Logger seam with structured tag namespaces

Issue: [#337](https://github.com/pkmetski/riffle/issues/337)

## Goal

Replace scattered `Log.d("RIFFLE_*", ...)` literals with a typed `Logger` seam keyed by a `LogChannel` enum. The team's `adb logcat -d | grep RIFFLE_*` debug recipes become first-class: tags live in one place, tests can intercept logs, and a CI grep prevents the literals from leaking back in.

## Non-goals

- Replacing non-`RIFFLE_*` `Log.*` calls (out of scope).
- Crash reporting / ACRA.
- Release-build channel gating (lambda `msg` arg leaves the door open; no enabledChannels set wired up now).
- Adding `Timber`.

## Survey of the current state

Branch `pkmetski/logger-seam`, snapshot at design time:

- `Log.[dweiv]("RIFFLE_*"` literals: 3 sites (across 3 files).
- Files using a `private const val LOG = "RIFFLE_*"` companion + `Log.d(LOG, …)`: 4 files.
- Total `RIFFLE_*`-tagged log sites in production code: **~20 across 7 files**:
  - `app/.../feature/reader/readaloud/ReadaloudController.kt` (8, two channels: RA + HANDOFF)
  - `app/.../feature/audiobook/AudiobookController.kt` (6, RIFFLE_AB)
  - `app/.../feature/reader/readaloud/ZipAudioDataSource.kt` (2, RIFFLE_RA)
  - `app/.../feature/audiobook/AudiobookPlayerViewModel.kt` (1, RIFFLE_HANDOFF)
  - `app/.../feature/reader/EpubReaderViewModel.kt` (1, RIFFLE_RA via literal)
  - `app/.../feature/reader/ReaderSyncFactory.kt` (1)
  - `app/.../feature/reader/session/ReadaloudSession.kt` (1)
- Distinct production channels in use: **`RIFFLE_RA`, `RIFFLE_AB`, `RIFFLE_HANDOFF`**. (`RIFFLE_TEST` exists in `androidTest` only and is not migrated.)
- No detekt, ktlint, or custom Android Lint module exists today — Slice 4 has to introduce its own enforcement vehicle.

## Architecture

### New module: `core/logging`

Android library, Hilt-enabled, JVM unit tests. Mirrors the layout of `core/data`/`core/network`. Wired into `settings.gradle.kts` and consumed by `app/`.

```
core/logging/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/com/riffle/core/logging/
    LogChannel.kt
    Logger.kt              // interface
    AndroidLogger.kt       // production impl, @Inject constructor
    RecordingLogger.kt     // test impl, in src/main so app tests can import it
    LoggingModule.kt       // @Module @InstallIn(SingletonComponent::class)
  src/test/kotlin/com/riffle/core/logging/
    RecordingLoggerTest.kt
```

`RecordingLogger` lives in `src/main` (not `src/test`) so it's importable from `app/`'s test source sets without test-fixture plumbing — it has no Android dependencies.

### Public API

```kotlin
package com.riffle.core.logging

enum class LogChannel(val tag: String) {
    Readaloud("RIFFLE_RA"),
    Audiobook("RIFFLE_AB"),
    Handoff("RIFFLE_HANDOFF"),
}

interface Logger {
    fun d(channel: LogChannel, t: Throwable? = null, msg: () -> String)
    fun w(channel: LogChannel, t: Throwable? = null, msg: () -> String)
    fun e(channel: LogChannel, t: Throwable? = null, msg: () -> String)
}
```

- `msg: () -> String` is a lambda so a future channel gate (`if (channel !in enabled) return`) is zero-cost on the hot path. Today every call evaluates the lambda.
- `Throwable?` defaults to null and is forwarded to `Log.[dwe](tag, msg, throwable)` when non-null.
- No `i`/`v` levels — neither is in use in the current call sites, and `LogChannel` already serves the categorisation that `i`/`v` would otherwise be used for. If a future site needs them, add then.

### `AndroidLogger`

```kotlin
class AndroidLogger @Inject constructor() : Logger {
    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) {
        if (t != null) Log.d(channel.tag, msg(), t) else Log.d(channel.tag, msg())
    }
    // w, e symmetrically
}
```

No test for `AndroidLogger` — it's a thin wrapper over `android.util.Log` which is unmockable on the JVM without Robolectric, and the wrapper's behaviour is mechanically obvious.

### `RecordingLogger`

```kotlin
class RecordingLogger : Logger {
    data class Record(val level: Level, val channel: LogChannel, val message: String, val throwable: Throwable?)
    enum class Level { D, W, E }

    private val _records = mutableListOf<Record>()
    val records: List<Record> get() = _records.toList()
    fun records(channel: LogChannel): List<Record> = _records.filter { it.channel == channel }
    fun clear() { _records.clear() }

    override fun d(channel: LogChannel, t: Throwable?, msg: () -> String) {
        _records += Record(Level.D, channel, msg(), t)
    }
    // w, e symmetrically
}
```

Thread-safety: not guaranteed (tests using it from multiple coroutines must synchronise). Documented in a KDoc comment.

### Hilt binding

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class LoggingModule {
    @Binds
    @Singleton
    abstract fun bindLogger(impl: AndroidLogger): Logger
}
```

All 7 call sites switch from `companion object { const val LOG = "RIFFLE_*" }` to constructor-injected `Logger`. ViewModels already use `@HiltViewModel @Inject constructor(...)` so adding a `Logger` parameter is mechanical. Singletons (`ReadaloudController`, `AudiobookController`) already have `@Inject constructor`.

### Sweep

For each of the 7 files:

1. Remove `import android.util.Log`.
2. Remove `companion object { private const val LOG = "RIFFLE_*" }` (and HANDOFF where present).
3. Add `private val logger: Logger` to the constructor.
4. Replace each `Log.d(LOG, "msg")` → `logger.d(LogChannel.Readaloud) { "msg" }`. Same for `w`/`e`. Throwable-bearing calls pass `t` as the named arg.
5. For sites in `ReadaloudController` that emit on **two** channels (RIFFLE_RA + RIFFLE_HANDOFF), keep both — they're independent.

### Slice 4: CI grep enforcement

A small gradle task in the root `build.gradle.kts` (or a make target — see Open question 1):

```
./gradlew checkRiffleLogTags
```

Implementation: a `Task` that walks `app/`, `core/`, scans `.kt` files, and fails if it finds `Log\.[dweiv]\("RIFFLE_` outside `core/logging/src/main/`. Wired into the `check` task so CI runs it automatically. Allow-list: `core/logging/src/main/.../LogChannel.kt`.

Why grep, not custom Lint: a custom `LintDetector` module requires `lint.gradle`, registry classes, AGP `lintChecks` wiring, and a meaningful test infrastructure for one rule. The grep task is ~20 lines and catches the exact thing the acceptance criterion forbids.

### Slice 5: docs

Append to `AGENTS.md`, under the existing "Agent skills" section:

> ### Logger channels
>
> Production log tags are typed in `core/logging/.../LogChannel.kt`. To add a new channel, add an enum entry; do not introduce a new `Log.d("RIFFLE_*", …)` literal. `RecordingLogger` is the test seam — inject it instead of mocking `Logger`. The `checkRiffleLogTags` CI task enforces this.

No separate doc file. The enum itself is the registry.

## Test plan

### `core/logging` unit tests (`RecordingLoggerTest`)

- `d/w/e` each record at the right level with the right channel.
- Throwable is captured when passed, absent when omitted.
- `records(channel)` filters correctly.
- `clear()` empties.
- Lambda `msg` is evaluated exactly once per call (today).

### Migrated call-site test

Pick **one** existing test that today asserts behaviour indirectly through log-free side effects, and rewrite it to also assert via `RecordingLogger`. Candidate: a `ReadaloudController` or `AudiobookController` unit test where a state transition emits a known `HANDOFF`/`AB` log. This proves the seam works end-to-end. The remaining migrated sites are covered by the existing tests for those files (logs are an observability concern, not a behavioural one — we don't need a new test per site).

### Lint task test

A unit test (or test-mode gradle invocation) that runs `checkRiffleLogTags` against a fixture tree containing one forbidden literal and asserts the task fails with a useful message. Covers the regression path.

### Validation gate (per CLAUDE.md "Validate before claiming done")

- `./gradlew test` green.
- `make harness-test` green (no behaviour change expected; this is a refactor).
- `./gradlew checkRiffleLogTags` green on the swept tree, red on a fixture with a literal.

No device verification needed — this refactor doesn't touch Readium, the WebView, or device-layer code. JVM tests cover the seam; existing instrumentation tests cover that swept sites still behave the same.

## Open questions

1. **Lint task vehicle** — gradle `Task` (lives in `build-logic` / root `build.gradle.kts`, runs in CI via the normal `./gradlew check`) or a `Makefile` target (consistent with the team's `make harness-test`)? Default: gradle task wired into `check`, since CI already runs `./gradlew test` and a `make` step would need separate CI plumbing. Confirm during plan-writing if not.

## Acceptance

- `core/logging` module exists with `LogChannel`, `Logger`, `AndroidLogger`, `RecordingLogger`, `LoggingModule`.
- All 7 production files inject `Logger` and use `LogChannel.*` — no `Log.[dweiv]("RIFFLE_…"` literals remain outside `core/logging/.../LogChannel.kt`.
- `companion object { … LOG = "RIFFLE_*" }` blocks deleted.
- `checkRiffleLogTags` gradle task added, wired to `check`, green.
- `AGENTS.md` has the Logger channels pointer.
- `./gradlew test` and `make harness-test` are green.

## Out of scope (explicit follow-ups)

- Seeding aspirational channels (`Progress`, `Download`, `Annotation`, `Reader`) — add when their first call site lands.
- Migrating non-`RIFFLE_*` `Log.*` calls.
- Release-build channel gating.
- Replacing `Log.*` in `androidTest` (`RIFFLE_TEST`).
