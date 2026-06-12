# Offline Audiobook From Bundle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the standalone audiobook player play from a downloaded readaloud bundle (download > bundle > stream), and make the offline library list bundle-only items — all behind one removable `BundleAudiobookSource` interface.

**Architecture:** The audiobook stack is driven by an `AudiobookSession` (track URLs + spans + timeline) plus book-absolute seconds, and `AudioPlayerService` already routes any non-http/file `mediaId` through `ZipAudioDataSource`. We add a third `AudiobookSession` producer — `BundleAudiobookSource`, implemented once by `StorytellerBundleAudiobookSource` (the only audiobook/library code that knows about readaloud bundles). The bundle session's track URLs are the bundle's zip-entry audio paths; the player points `SharedBundle.current` at the bundle file before preparing. Persistence/reconcile/the ADR-0030 sweep are inherited unchanged because position stays book-absolute seconds.

**Tech Stack:** Kotlin, Hilt DI, Media3/ExoPlayer, kotlinx.coroutines, JUnit. Pure-JVM unit tests in `core/domain`, Robolectric/JVM tests in `core/data` and `app`.

**Setup for every test/build command below:**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/plamen.kmetski/conductor/workspaces/riffle/sao-paulo
```

---

## File Structure

- **Create** `core/domain/src/main/kotlin/com/riffle/core/domain/BundleAudiobookSource.kt` — the interface both consumers depend on.
- **Create** `core/data/src/main/kotlin/com/riffle/core/data/BundleAudiobookSessionBuilder.kt` — pure mapping `ReadaloudTrack` + bundle `File` → `AudiobookSession`.
- **Create** `core/data/src/main/kotlin/com/riffle/core/data/StorytellerBundleAudiobookSource.kt` — the single implementation (link→bundle translation, snapshot, builder call).
- **Modify** `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookRepository.kt` — add `localZipFile` to `AudiobookSession`.
- **Modify** `core/domain/src/main/kotlin/com/riffle/core/domain/LibraryItemOfflineAvailability.kt` — add dependency + OR clause.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt` — accept `localZipFile`, set `SharedBundle.current`.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt` — insert bundle source into the resolution chain.
- **Modify** `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` — bind `BundleAudiobookSource`, update the `LibraryItemOfflineAvailability` provider.
- **Test (create)** `core/data/.../BundleAudiobookSessionBuilderTest.kt`, `core/data/.../StorytellerBundleAudiobookSourceTest.kt`.
- **Test (modify)** `core/domain/.../LibraryItemOfflineAvailabilityTest.kt`.

No database migration. No `AudioPlayerService` change (its scheme routing already covers zip-entry mediaIds).

---

## Task 1: Carry the local zip file on `AudiobookSession`

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookRepository.kt`

- [ ] **Step 1: Add the field**

In `AudiobookRepository.kt`, add an import at the top of the file (after the `package` line):
```kotlin
import java.io.File
```
Then add a field to the `AudiobookSession` data class, after `serverLastUpdate`:
```kotlin
    val serverLastUpdate: Long = 0,
    // The local zip archive backing zip-entry track URLs (a downloaded bundle), or null when tracks
    // are HTTP/file URLs. The player points the playback service at this file before preparing.
    val localZipFile: File? = null,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core:domain:compileDebugKotlin 2>/dev/null || ./gradlew :core:domain:test --tests 'nonexistent' 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (no compile errors). (Existing two producers default `localZipFile` to null, so nothing else breaks.)

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/AudiobookRepository.kt
git commit -m "feat(audiobook): carry an optional local zip file on AudiobookSession"
```

---

## Task 2: Define the `BundleAudiobookSource` interface

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/BundleAudiobookSource.kt`

- [ ] **Step 1: Write the interface**

```kotlin
package com.riffle.core.domain

/**
 * Produces an [AudiobookSession] for an ABS item from a downloaded bundle's audio, when one exists.
 * This is the third [AudiobookSession] producer (alongside [AudiobookRepository] for streaming and
 * [AudiobookDownloadRepository] for the dedicated download); the player prefers a dedicated download,
 * then a bundle, then streaming.
 *
 * All knowledge of which bundle type backs the audio (today: a Storyteller readaloud bundle) lives in
 * the single implementation. Removing or replacing that bundle type is a one-class change behind this
 * interface; the audiobook stack and the library are unaffected.
 */
interface BundleAudiobookSource {
    /** A playable session backed by a downloaded bundle's audio for this ABS item, or null if none. */
    suspend fun localSession(serverId: String, itemId: String): AudiobookSession?

    /**
     * True when a downloaded bundle can satisfy this ABS item's audio offline. Synchronous so it backs
     * the library's offline filter without making that predicate suspend.
     */
    fun isAvailableOffline(serverId: String, itemId: String): Boolean
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core:domain:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/BundleAudiobookSource.kt
git commit -m "feat(audiobook): BundleAudiobookSource interface for offline bundle audio"
```

---

## Task 3: Pure session builder (`ReadaloudTrack` + bundle file → `AudiobookSession`)

This is the meat of the mapping and is pure/deterministic, so it gets thorough TDD.

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/BundleAudiobookSessionBuilder.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/BundleAudiobookSessionBuilderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BundleAudiobookSessionBuilderTest {

    private val bundle = File("/tmp/bundle.epub")

    private fun clip(href: String, audio: String, begin: Double, end: Double) =
        MediaOverlayClip(textFragmentRef = href, audioSrc = audio, clipBeginSec = begin, clipEndSec = end)

    @Test
    fun `builds one span per distinct audio file with cumulative offsets`() {
        val track = ReadaloudTrack(
            listOf(
                clip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0),
                clip("c1.xhtml#s1", "audio/0.mp3", 30.0, 60.0),
                clip("c2.xhtml#s0", "audio/1.mp3", 0.0, 45.0),
            ),
        )

        val session = buildBundleAudiobookSession(track, bundle)!!

        // Two distinct audio files → two spans; durations are each file's last clipEnd.
        assertEquals(listOf("audio/0.mp3", "audio/1.mp3"), session.trackUrls)
        assertEquals(2, session.tracks.size)
        assertEquals(0.0, session.tracks[0].startOffsetSec, 1e-9)
        assertEquals(60.0, session.tracks[0].durationSec, 1e-9)
        assertEquals(60.0, session.tracks[1].startOffsetSec, 1e-9) // starts after file 0
        assertEquals(45.0, session.tracks[1].durationSec, 1e-9)
        assertEquals(105.0, session.timeline.durationSec, 1e-9)    // 60 + 45
        assertEquals(bundle, session.localZipFile)
        assertEquals(0.0, session.serverCurrentTimeSec, 1e-9)
        assertEquals(0L, session.serverLastUpdate)
    }

    @Test
    fun `derives one chapter per distinct chapter href with boundaries on the global timeline`() {
        val track = ReadaloudTrack(
            listOf(
                clip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0),
                clip("c2.xhtml#s0", "audio/0.mp3", 30.0, 60.0), // chapter 2 starts at global 30s
                clip("c2.xhtml#s1", "audio/1.mp3", 0.0, 45.0),  // still chapter 2 (global 60s)
            ),
        )

        val chapters = buildBundleAudiobookSession(track, bundle)!!.timeline.chapters

        assertEquals(2, chapters.size)
        assertEquals(0.0, chapters[0].startSec, 1e-9)
        assertEquals(30.0, chapters[0].endSec, 1e-9)
        assertEquals(30.0, chapters[1].startSec, 1e-9)
        assertEquals(105.0, chapters[1].endSec, 1e-9) // last chapter ends at total duration
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
    }

    @Test
    fun `returns null when the track has no clips`() {
        assertNull(buildBundleAudiobookSession(ReadaloudTrack(emptyList()), bundle))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.riffle.core.data.BundleAudiobookSessionBuilderTest' 2>&1 | tail -15`
Expected: FAIL — `buildBundleAudiobookSession` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.AudiobookTrackSpan
import com.riffle.core.domain.ReadaloudTrack
import java.io.File

/**
 * Maps a bundle's Media Overlay [ReadaloudTrack] to a playable [AudiobookSession]: one span per
 * distinct audio file (the same playlist order Readaloud queues), with cumulative book-absolute
 * offsets, and chapters derived from the bundle's distinct chapter hrefs. The bundle audio is the
 * same ABS source re-split into segments, so these spans are simply a different — but still
 * contiguous — covering of the book timeline; the player's seek math works on any such covering.
 *
 * Track URLs are the audio files' zip-entry paths (a clip's `audioSrc`); [AudioPlayerService] routes
 * any non-http/file mediaId through `ZipAudioDataSource`, reading from [bundle] via `SharedBundle`.
 * Returns null when the bundle has no Media Overlay clips.
 *
 * Chapter titles are numbered ("Chapter N") — the SMIL overlay carries chapter hrefs and timings but
 * not display titles. This is the accepted v1 offline degradation (a later pass can persist ABS
 * chapter titles for offline use).
 */
internal fun buildBundleAudiobookSession(track: ReadaloudTrack, bundle: File): AudiobookSession? {
    val files = track.clips.map { it.audioSrc }.distinct()
    if (files.isEmpty()) return null

    val durByFile = track.clips.groupBy { it.audioSrc }.mapValues { (_, cs) -> cs.maxOf { it.clipEndSec } }
    var acc = 0.0
    val spans = files.mapIndexed { i, src ->
        val d = durByFile[src] ?: 0.0
        AudiobookTrackSpan(index = i, startOffsetSec = acc, durationSec = d).also { acc += d }
    }
    val totalDuration = acc
    val fileStart: Map<String, Double> = files.zip(spans).associate { (src, span) -> src to span.startOffsetSec }

    // Chapter boundaries: each distinct chapter's first clip mapped onto the global timeline.
    val starts = (0 until track.chapterCount).mapNotNull { idx ->
        track.firstClipOfChapter(idx)?.let { clip -> (fileStart[clip.audioSrc] ?: 0.0) + clip.clipBeginSec }
    }.sorted()
    val chapters = starts.mapIndexed { i, start ->
        AudiobookChapter(
            index = i,
            startSec = start,
            endSec = starts.getOrNull(i + 1) ?: totalDuration,
            title = "Chapter ${i + 1}",
        )
    }

    return AudiobookSession(
        trackUrls = files,
        tracks = spans,
        timeline = AudiobookTimeline(durationSec = totalDuration, chapters = chapters),
        serverCurrentTimeSec = 0.0,
        serverLastUpdate = 0L,
        localZipFile = bundle,
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.riffle.core.data.BundleAudiobookSessionBuilderTest' 2>&1 | tail -15`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/BundleAudiobookSessionBuilder.kt core/data/src/test/kotlin/com/riffle/core/data/BundleAudiobookSessionBuilderTest.kt
git commit -m "feat(audiobook): map a readaloud bundle's overlay track to an AudiobookSession"
```

---

## Task 4: `StorytellerBundleAudiobookSource` implementation

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/StorytellerBundleAudiobookSource.kt`
- Test: `core/data/src/test/kotlin/com/riffle/core/data/StorytellerBundleAudiobookSourceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StorytellerBundleAudiobookSourceTest {

    private val link = ReadaloudLink(
        storytellerServerId = "st", storytellerBookId = "book-1",
        absServerId = "abs", absLibraryItemId = "item-1", userConfirmed = true,
    )

    private class FakeLinks(private val all: List<ReadaloudLink>) : ReadaloudLinkRepository {
        override fun observeAll(): Flow<List<ReadaloudLink>> = MutableStateFlow(all)
        override fun observeLinkedAbsItemIds() = MutableStateFlow(all.map { it.absLibraryItemId }.toSet())
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String) =
            all.firstOrNull { it.absServerId == absServerId && it.absLibraryItemId == absLibraryItemId }
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            all.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) = Unit
        override suspend fun countForServer(serverId: String) = all.count { it.absServerId == serverId }
    }

    private class FakeAudio(
        private val bundle: File?,
        private val track: ReadaloudTrack?,
    ) : ReadaloudAudioRepository {
        override fun isAudioAvailable(serverId: String, itemId: String) = bundle != null
        override fun bundleFile(serverId: String, itemId: String) = bundle
        override suspend fun readTrack(serverId: String, itemId: String) = track
        override suspend fun probeSizeBytes(serverId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(serverId: String, bookId: String, onProgress: (Long, Long) -> Unit) =
            com.riffle.core.domain.AudioDownloadResult.Success
        override suspend fun removeAudio(serverId: String, itemId: String) = 0L
    }

    private val track = ReadaloudTrack(
        listOf(MediaOverlayClip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0)),
    )
    private val bundle = File("/tmp/book-1.epub")

    @Test
    fun `localSession resolves link then bundle then builds a session`() = runTest {
        val source = StorytellerBundleAudiobookSource(
            readaloudLinkRepository = FakeLinks(listOf(link)),
            readaloudAudioRepository = FakeAudio(bundle, track),
            scope = backgroundScope,
        )

        val session = source.localSession("abs", "item-1")!!

        assertEquals(listOf("audio/0.mp3"), session.trackUrls)
        assertEquals(bundle, session.localZipFile)
        assertEquals(30.0, session.timeline.durationSec, 1e-9)
    }

    @Test
    fun `localSession is null with no link, no bundle, or no overlay`() = runTest {
        assertNull(
            StorytellerBundleAudiobookSource(FakeLinks(emptyList()), FakeAudio(bundle, track), backgroundScope)
                .localSession("abs", "item-1"),
        )
        assertNull(
            StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(null, track), backgroundScope)
                .localSession("abs", "item-1"),
        )
        assertNull(
            StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(bundle, null), backgroundScope)
                .localSession("abs", "item-1"),
        )
    }

    @Test
    fun `isAvailableOffline reflects the link snapshot and bundle presence`() = runTest {
        val present = StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(bundle, track), backgroundScope)
        advanceUntilIdle() // let the snapshot collector run
        assertTrue(present.isAvailableOffline("abs", "item-1"))
        assertFalse(present.isAvailableOffline("abs", "other"))

        val noBundle = StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(null, track), backgroundScope)
        advanceUntilIdle()
        assertFalse(noBundle.isAvailableOffline("abs", "item-1"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.riffle.core.data.StorytellerBundleAudiobookSourceTest' 2>&1 | tail -15`
Expected: FAIL — `StorytellerBundleAudiobookSource` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The sole [BundleAudiobookSource] implementation, and the only audiobook/library code that knows the
 * offline audio can come from a Storyteller readaloud bundle (ADR 0023). It translates the player's
 * ABS `(serverId, itemId)` to the bundle's Storyteller `(serverId, bookId)` via the [ReadaloudLink],
 * then maps the bundle's Media Overlay track to an [AudiobookSession] ([buildBundleAudiobookSession]).
 *
 * [isAvailableOffline] must be synchronous (it backs the library's offline filter), so the suspend
 * link lookup is replaced on that path by a [linksByAbsItem] snapshot kept fresh from
 * [ReadaloudLinkRepository.observeAll] on an application-lifetime [scope] — the same self-managed IO
 * scope pattern as [CrossEpubIndexBuilderService].
 */
class StorytellerBundleAudiobookSource(
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    scope: CoroutineScope,
) : BundleAudiobookSource {

    // ABS "serverId/itemId" -> link, so isAvailableOffline is a synchronous lookup.
    @Volatile
    private var linksByAbsItem: Map<String, ReadaloudLink> = emptyMap()

    init {
        scope.launch {
            readaloudLinkRepository.observeAll().collect { links ->
                linksByAbsItem = links.associateBy { absKey(it.absServerId, it.absLibraryItemId) }
            }
        }
    }

    override suspend fun localSession(serverId: String, itemId: String): AudiobookSession? {
        val link = readaloudLinkRepository.findByAbsItem(serverId, itemId) ?: return null
        val bundle = readaloudAudioRepository.bundleFile(link.storytellerServerId, link.storytellerBookId)
            ?: return null
        val track = readaloudAudioRepository.readTrack(link.storytellerServerId, link.storytellerBookId)
            ?: return null
        return buildBundleAudiobookSession(track, bundle)
    }

    override fun isAvailableOffline(serverId: String, itemId: String): Boolean {
        val link = linksByAbsItem[absKey(serverId, itemId)] ?: return false
        return readaloudAudioRepository.isAudioAvailable(link.storytellerServerId, link.storytellerBookId)
    }

    private fun absKey(serverId: String, itemId: String) = "$serverId/$itemId"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:data:testDebugUnitTest --tests 'com.riffle.core.data.StorytellerBundleAudiobookSourceTest' 2>&1 | tail -15`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/StorytellerBundleAudiobookSource.kt core/data/src/test/kotlin/com/riffle/core/data/StorytellerBundleAudiobookSourceTest.kt
git commit -m "feat(audiobook): StorytellerBundleAudiobookSource (link translation + offline snapshot)"
```

---

## Task 5: `AudiobookController` points the service at the bundle file

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt`

The controller already connects to `AudioPlayerService`, which restores a non-http/file `mediaId` to a `zipaudio://` URI and reads it from `SharedBundle.current`. So the only change is: set `SharedBundle.current` to the session's local zip file before queueing.

- [ ] **Step 1: Add the import**

In `AudiobookController.kt`, add to the imports (the readaloud package is in the same `:app` module, so `internal` `SharedBundle` is accessible):
```kotlin
import com.riffle.app.feature.reader.readaloud.SharedBundle
import java.io.File
```

- [ ] **Step 2: Add the parameter and set the bundle**

Change `prepare`'s signature to accept the optional zip file, and set `SharedBundle.current` at the top of the body. Find:
```kotlin
    suspend fun prepare(
        trackUrls: List<String>,
        spans: List<AudiobookTrackSpan>,
        durationSec: Double,
        startAtSec: Double,
    ) {
        this.spans = spans
        this.durationSec = durationSec
        val c = ensureConnected() ?: return
```
Replace with:
```kotlin
    suspend fun prepare(
        trackUrls: List<String>,
        spans: List<AudiobookTrackSpan>,
        durationSec: Double,
        startAtSec: Double,
        localZipFile: File? = null,
    ) {
        this.spans = spans
        this.durationSec = durationSec
        // Bundle-backed audio: the track mediaIds are zip-entry paths the service reads from this file
        // via SharedBundle (the same channel Readaloud uses). Null for HTTP/file sessions, where the
        // service never consults SharedBundle.
        if (localZipFile != null) SharedBundle.current = localZipFile
        val c = ensureConnected() ?: return
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL. (All existing `prepare(...)` callers omit `localZipFile`, defaulting to null.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt
git commit -m "feat(audiobook): controller points the service at a bundle file when given one"
```

---

## Task 6: Bind `BundleAudiobookSource` + wire the offline library check (DI + predicate)

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/LibraryItemOfflineAvailability.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`
- Test: `core/domain/src/test/kotlin/com/riffle/core/domain/LibraryItemOfflineAvailabilityTest.kt`

- [ ] **Step 1: Write the failing test**

Open `LibraryItemOfflineAvailabilityTest.kt`. First add a fake source next to the other `private class Fake…` definitions at the bottom (same package, so no import needed):
```kotlin
    private class FakeBundleAudiobookSource(
        private val offlineIds: Set<String> = emptySet(),
    ) : BundleAudiobookSource {
        override suspend fun localSession(serverId: String, itemId: String): AudiobookSession? = null
        override fun isAvailableOffline(serverId: String, itemId: String) = "$serverId/$itemId" in offlineIds
    }
```
Every existing test constructs `LibraryItemOfflineAvailability(...)` with three arguments — there are **six** such call sites. Add the new fourth argument to each one:
```kotlin
            bundleAudiobookSource = FakeBundleAudiobookSource(),
```
For example, the first existing test becomes:
```kotlin
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(downloaded = true),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(),
        )
```
Apply the identical fourth argument to all six existing constructions. Then add the two new tests (the `item(...)` helper defaults to `serverId = "s1"`, `id = "i1"`, so the offline key is `"s1/i1"`):
```kotlin
    @Test
    fun `item with no ebook or audiobook download is offline-available when its bundle is downloaded`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(setOf("s1/i1")),
        )

        assertTrue(availability.isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)))
    }

    @Test
    fun `item with no downloads and no bundle is not offline-available`() {
        val availability = LibraryItemOfflineAvailability(
            epubRepository = FakeEpubRepository(),
            pdfRepository = FakePdfRepository(),
            audiobookDownloadRepository = FakeAudiobookDownloadRepository(),
            bundleAudiobookSource = FakeBundleAudiobookSource(emptySet()),
        )

        assertFalse(availability.isAvailableOffline(item(EbookFormat.Unsupported, hasAudio = true)))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:domain:test --tests 'com.riffle.core.domain.LibraryItemOfflineAvailabilityTest' 2>&1 | tail -20`
Expected: FAIL — `LibraryItemOfflineAvailability` has no `bundleAudiobookSource` parameter (compile error), since the production class is not yet updated.

- [ ] **Step 3: Update the predicate**

In `LibraryItemOfflineAvailability.kt`, add the constructor dependency and the OR clause:
```kotlin
class LibraryItemOfflineAvailability(
    private val epubRepository: EpubRepository,
    private val pdfRepository: PdfRepository,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
    private val bundleAudiobookSource: BundleAudiobookSource,
) {
    fun isAvailableOffline(item: LibraryItem): Boolean {
        val ebookAvailable = when (item.ebookFormat) {
            EbookFormat.Epub ->
                epubRepository.isDownloaded(item.serverId, item.id) || epubRepository.isCached(item.serverId, item.id)
            EbookFormat.Pdf ->
                pdfRepository.isDownloaded(item.serverId, item.id) || pdfRepository.isCached(item.serverId, item.id)
            EbookFormat.Unsupported -> false
        }
        return ebookAvailable ||
            audiobookDownloadRepository.isDownloaded(item.serverId, item.id) ||
            bundleAudiobookSource.isAvailableOffline(item.serverId, item.id)
    }
}
```
Also update the class KDoc's last sentence to mention the bundle:
```kotlin
 * cache), so the audio side is a plain `isDownloaded` check (ADR 0029). An item is ALSO offline if a
 * downloaded readaloud bundle can supply its audio ([BundleAudiobookSource]).
```

- [ ] **Step 4: Provide the binding + update the provider in `DataModule.kt`**

Add a provider for the new source. Place it near `provideReadaloudAudioRepository` (around line 378). It needs `ReadaloudLinkRepository`, `ReadaloudAudioRepository`, and a fresh app-lifetime scope:
```kotlin
        @Provides
        @Singleton
        fun provideBundleAudiobookSource(
            readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
            readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
        ): com.riffle.core.domain.BundleAudiobookSource =
            StorytellerBundleAudiobookSource(
                readaloudLinkRepository = readaloudLinkRepository,
                readaloudAudioRepository = readaloudAudioRepository,
                scope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                ),
            )
```
Then update `provideLibraryItemOfflineAvailability` (around line 418) to take and pass the source. Find:
```kotlin
        fun provideLibraryItemOfflineAvailability(
            epubRepository: EpubRepository,
            pdfRepository: PdfRepository,
            audiobookDownloadRepository: AudiobookDownloadRepository,
        ): LibraryItemOfflineAvailability =
            LibraryItemOfflineAvailability(epubRepository, pdfRepository, audiobookDownloadRepository)
```
Replace with:
```kotlin
        fun provideLibraryItemOfflineAvailability(
            epubRepository: EpubRepository,
            pdfRepository: PdfRepository,
            audiobookDownloadRepository: AudiobookDownloadRepository,
            bundleAudiobookSource: com.riffle.core.domain.BundleAudiobookSource,
        ): LibraryItemOfflineAvailability =
            LibraryItemOfflineAvailability(epubRepository, pdfRepository, audiobookDownloadRepository, bundleAudiobookSource)
```
> NOTE: if `EpubRepository` / `PdfRepository` / `AudiobookDownloadRepository` / `LibraryItemOfflineAvailability` are already imported at the top of `DataModule.kt` (they are — see the existing provider), no new top-level imports are required; the fully-qualified names above avoid touching the import block.

- [ ] **Step 5: Run the predicate test to verify it passes**

Run: `./gradlew :core:domain:test --tests 'com.riffle.core.domain.LibraryItemOfflineAvailabilityTest' 2>&1 | tail -20`
Expected: PASS (existing tests + 2 new).

- [ ] **Step 6: Verify DI graph compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -12`
Expected: BUILD SUCCESSFUL (Hilt resolves `BundleAudiobookSource`). If a Hilt androidTest graph error appears, check whether a test module needs the new binding (see memory: TestDatabaseModule hand-provides bindings) — but this binding is in the production `DataModule`, so app debug should be unaffected.

- [ ] **Step 7: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/LibraryItemOfflineAvailability.kt core/domain/src/test/kotlin/com/riffle/core/domain/LibraryItemOfflineAvailabilityTest.kt core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
git commit -m "feat(library): count a downloaded bundle as offline-available; bind BundleAudiobookSource"
```

---

## Task 7: Insert the bundle source into the player's resolution chain

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt`

- [ ] **Step 1: Add the constructor dependency**

In the `@HiltViewModel` constructor, after `private val audiobookDownloadRepository: com.riffle.core.domain.AudiobookDownloadRepository,` add:
```kotlin
    private val bundleAudiobookSource: com.riffle.core.domain.BundleAudiobookSource,
```

- [ ] **Step 2: Insert the bundle into the resolution chain**

Find (in `init`):
```kotlin
            val session = if (serverId.isEmpty()) null
                else audiobookDownloadRepository.localSession(serverId, itemId)
                    ?: audiobookRepository.openSession(serverId, itemId)
```
Replace with:
```kotlin
            // Prefer a dedicated audiobook download, then a downloaded readaloud bundle's audio, then
            // stream from ABS (connectivity-independent: a local copy always beats streaming).
            val session = if (serverId.isEmpty()) null
                else audiobookDownloadRepository.localSession(serverId, itemId)
                    ?: bundleAudiobookSource.localSession(serverId, itemId)
                    ?: audiobookRepository.openSession(serverId, itemId)
```

- [ ] **Step 3: Pass the local zip file to the controller**

Find the `controller.prepare(...)` call in `init`:
```kotlin
            controller.prepare(
                trackUrls = session.trackUrls,
                spans = session.tracks,
                durationSec = session.timeline.durationSec,
                startAtSec = resumeSec,
            )
```
Replace with:
```kotlin
            controller.prepare(
                trackUrls = session.trackUrls,
                spans = session.tracks,
                durationSec = session.timeline.durationSec,
                startAtSec = resumeSec,
                localZipFile = session.localZipFile,
            )
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the audiobook player VM tests (regression)**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.riffle.app.feature.audiobook.*' 2>&1 | tail -20`
Expected: PASS. If `AudiobookPlayerViewModelTest` constructs the VM directly, add the new `bundleAudiobookSource` argument with a fake whose `localSession` returns null and `isAvailableOffline` returns false, so the existing download/stream resolution behavior is unchanged.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt app/src/test/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModelTest.kt
git commit -m "feat(audiobook): play from a downloaded bundle when no dedicated download exists"
```

---

## Task 8: Full test sweep

**Files:** none (verification).

- [ ] **Step 1: Run the full JVM test suite**

Run: `./gradlew test 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. (CI uses `./gradlew test`; module-specific runs miss pure-JVM modules.) Pre-existing flakes noted in project memory — `replaceAllForLibrary` emission tests and `AutoFollowJsTest` paginated-snap — are not caused by this change; re-run once if they appear. Any other failure is in scope: fix the cause or the test.

- [ ] **Step 2: Build the debug APK to confirm the Hilt graph is whole**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -12`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit (only if a fix was required in Steps 1–2)**

```bash
git add -A && git commit -m "test(audiobook): fixes from full sweep for offline bundle playback"
```

---

## Task 9: Device verification

**Files:** none (manual verification on a real device — emulator audio-HAL and Chrome-55 WebView caveats make the emulator unreliable for audio; see project memory).

- [ ] **Step 1: Verify offline playback**

Set up a readaloud-matched audiobook with its bundle downloaded and **no** dedicated audiobook download (recipe in `reference_readaloud_e2e_test_setup` memory). Put the device in airplane mode. Open the audiobook player. Expected: it plays from the bundle (audio comes from `ZipAudioDataSource`; check Logcat for the `zipaudio` reads, no HTTP attempts), position/seek/chapter-prev-next work, chapter titles read "Chapter N".

- [ ] **Step 2: Verify resolution precedence**

With the device online and a dedicated audiobook download present, confirm the player still uses the download (not the bundle) — behavior unchanged. Remove the dedicated download; confirm it now uses the bundle even while online.

- [ ] **Step 3: Verify the offline library listing**

Offline, with only the bundle downloaded for a matched item (no ebook/audiobook download), confirm the item appears in the library lists (All Books, In Progress, series/collection groupings) instead of being filtered out.

- [ ] **Step 4: Verify progress durability (inherited)**

Listen offline for a bit, pause/close, kill the app, reconnect. Confirm the listen position persisted (it resumes correctly) and reaches ABS once online (the durable store + ADR-0030 sweep carry it — no new work here, just confirm the bundle path didn't break it).

---

## Self-Review notes

- **Spec coverage:** isolation seam (Tasks 2, 4) ✓; download>bundle>stream (Task 7) ✓; offline library includes bundle (Task 6) ✓; chapters from bundle nav with numbered-title degradation (Task 3) ✓; no DB migration / no new download path ✓; no `AudioPlayerService` change (scheme routing already covers zip entries) ✓.
- **Removal property:** deleting `provideBundleAudiobookSource` + `StorytellerBundleAudiobookSource` + `BundleAudiobookSessionBuilder`, dropping the chain line in Task 7, and reverting the Task 6 provider/predicate edit removes the feature with no other code touched — the `localZipFile` field defaults to null and is harmless if left.
- **Type consistency:** `buildBundleAudiobookSession(track, bundle)` signature matches its test and its single caller; `prepare(..., localZipFile)` matches the VM call; `BundleAudiobookSource` methods match the fake and the impl.
