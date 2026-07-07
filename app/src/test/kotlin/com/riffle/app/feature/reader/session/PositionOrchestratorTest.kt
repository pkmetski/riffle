package com.riffle.app.feature.reader.session

import android.net.FakeUri
import com.riffle.app.feature.reader.PositionSaveCoordinator
import com.riffle.core.domain.ReadingPositionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@OptIn(ExperimentalCoroutinesApi::class)
class PositionOrchestratorTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // --- Fakes ---

    private class FakeReadingPositionStore : ReadingPositionStore {
        val savedTimestamps = mutableListOf<Pair<Long, String>>()
        var localUpdatedAt: Long = 100L
        override suspend fun save(sourceId: String, itemId: String, payload: String) { }
        override suspend fun load(sourceId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = localUpdatedAt
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) {
            savedTimestamps.add(millis to itemId)
        }
    }

    private class FakePositionSaveCoordinator {
        val saved = mutableListOf<String>()
        val coordinator = PositionSaveCoordinator<String>(
            savePosition = { pos -> saved.add(pos) },
            updateProgress = { },
        )
    }

    // Bypasses android.net.Uri by allocating AbsoluteUrl via Unsafe + FakeUri (same pattern as
    // SearchControllerTest.buildLocator).
    @Suppress("UNCHECKED_CAST")
    private fun buildLocator(
        href: String,
        progression: Double = 0.0,
        totalProgression: Double? = null,
    ): Locator {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(AbsoluteUrl::class.java) as AbsoluteUrl
        AbsoluteUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri(href))
        return Locator(
            href = url,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = progression,
                totalProgression = totalProgression,
            ),
        )
    }

    private fun makeOrchestrator(
        itemId: String = "item1",
        sourceId: String = "server1",
    ): Triple<PositionOrchestrator, FakePositionSaveCoordinator, FakeReadingPositionStore> {
        val fakeSave = FakePositionSaveCoordinator()
        val fakeStore = FakeReadingPositionStore()
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val orchestrator = PositionOrchestrator(scope)
        orchestrator.bindBook(
            itemId = itemId,
            sourceId = sourceId,
            positionSaveCoordinator = fakeSave.coordinator,
            readingPositionStore = fakeStore,
            spinePositionCounts = MutableStateFlow(emptyList<String>() to emptyList()),
        )
        return Triple(orchestrator, fakeSave, fakeStore)
    }

    // --- Tests ---

    /** (1) onPositionChanged updates currentLocator, href, progression, totalProgression in one tick */
    @Test
    fun `onPositionChanged updates all position state flows`() = runTest(testDispatcher) {
        val (orchestrator, _, _) = makeOrchestrator()
        // First emission is swallowed by the initialLocatorSeen gate; fire a "seed" first.
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.0))
        // Second emission is the real one
        orchestrator.onPositionChanged(buildLocator("ch2.html", 0.5, totalProgression = 0.3))

        assertEquals("ch2.html", orchestrator.currentLocator.value?.href?.toString())
        assertEquals("ch2.html", orchestrator.currentLocatorHref.value)
        assertEquals(0.5f, orchestrator.currentLocatorProgression.value)
        assertEquals(0.3f, orchestrator.currentLocatorTotalProgression.value)
    }

    /** (2) onPositionChanged saves position to coordinator after the initial-locator gate */
    @Test
    fun `onPositionChanged saves position to coordinator after initial locator seen`() = runTest(testDispatcher) {
        val (orchestrator, fakeSave, _) = makeOrchestrator()
        // First emission — triggers initialLocatorSeen guard and returns early (no save)
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.0))
        assertEquals(0, fakeSave.saved.size)
        // Second emission — saves
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.5))
        assertEquals(1, fakeSave.saved.size)
    }

    /** (3) requestServerJump emits to serverLocatorEvents */
    @Test
    fun `requestServerJump emits locator to serverLocatorEvents`() = runTest(testDispatcher) {
        val (orchestrator, _, _) = makeOrchestrator()
        val locator = buildLocator("ch3.html", 0.1)
        val received = mutableListOf<Locator>()
        val job = launch { orchestrator.serverLocatorEvents.collect { received.add(it) } }
        orchestrator.requestServerJump(locator)
        job.cancel()
        assertTrue("Expected at least one event", received.isNotEmpty())
        assertEquals("ch3.html", received.first().href.toString())
    }

    /** (4) markSuppressNextServerLocator causes the next requestServerJumpWithSuppressCheck to be dropped */
    @Test
    fun `markSuppressNextServerLocator drops next requestServerJumpWithSuppressCheck`() = runTest(testDispatcher) {
        val scope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val orchestrator = PositionOrchestrator(scope)
        orchestrator.bindBook(
            itemId = "item1",
            sourceId = "server1",
            positionSaveCoordinator = FakePositionSaveCoordinator().coordinator,
            readingPositionStore = FakeReadingPositionStore(),
            spinePositionCounts = MutableStateFlow(emptyList<String>() to emptyList()),
        )
        orchestrator.markSuppressNextServerLocator()

        val received = mutableListOf<Locator>()
        val job = launch { orchestrator.serverLocatorEvents.collect { received.add(it) } }

        // This jump should be suppressed
        orchestrator.requestServerJumpWithSuppressCheck(buildLocator("ch1.html", 0.0))
        assertEquals("Suppressed jump should not be emitted", 0, received.size)

        // Next jump should go through (suppression consumed)
        orchestrator.requestServerJumpWithSuppressCheck(buildLocator("ch2.html", 0.5))
        assertEquals("Next jump should be emitted", 1, received.size)
        job.cancel()
    }

    /** (5) setReturnAnchor + consumeReturnAnchor round-trip; consume clears the value */
    @Test
    fun `setReturnAnchor and consumeReturnAnchor round-trip`() = runTest(testDispatcher) {
        val (orchestrator, _, _) = makeOrchestrator()
        val locator = buildLocator("ch5.html", 0.4)
        orchestrator.setReturnAnchor(locator)
        val consumed = orchestrator.consumeReturnAnchor()
        assertEquals("ch5.html", consumed?.href?.toString())
        assertNull("consumeReturnAnchor should clear the value", orchestrator.consumeReturnAnchor())
    }

    /** (6) bindBook re-initialises all state for a new book */
    @Test
    fun `bindBook resets state for a new book`() = runTest(testDispatcher) {
        val (orchestrator, _, _) = makeOrchestrator()
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.5))
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.7))

        // Bind a new book
        orchestrator.bindBook(
            itemId = "item2",
            sourceId = "server1",
            positionSaveCoordinator = FakePositionSaveCoordinator().coordinator,
            readingPositionStore = FakeReadingPositionStore(),
            spinePositionCounts = MutableStateFlow(emptyList<String>() to emptyList()),
        )

        assertNull("currentLocator should reset after bindBook", orchestrator.currentLocator.value)
        assertNull(orchestrator.currentLocatorHref.value)
        assertNull(orchestrator.currentLocatorProgression.value)
    }

    /** (7) initialLocatorSeen skips save on first onPositionChanged and allows on second */
    @Test
    fun `initialLocatorSeen skips save on first onPositionChanged and allows on second`() = runTest(testDispatcher) {
        val (orchestrator, fakeSave, _) = makeOrchestrator()
        // First emission must not save (it's the navigator restore, not user progress)
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.0))
        assertTrue("No save on first emission", fakeSave.saved.isEmpty())
        // Subsequent emission saves
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.3))
        assertEquals(1, fakeSave.saved.size)
    }

    /** (8) snapshotLastLocator returns the last reported locator after onPositionChanged */
    @Test
    fun `snapshotLastLocator returns last known locator`() = runTest(testDispatcher) {
        val (orchestrator, _, _) = makeOrchestrator()
        assertNull(orchestrator.snapshotLastLocator())
        orchestrator.onPositionChanged(buildLocator("ch8.html", 0.6))
        assertEquals("ch8.html", orchestrator.snapshotLastLocator()?.href?.toString())
    }

    /** (9) pendingServerJumpStamp causes onPositionChanged to record the adopted server timestamp */
    @Test
    fun `pendingServerJumpStamp is recorded to ReadingPositionStore on next onPositionChanged`() = runTest(testDispatcher) {
        val (orchestrator, _, fakeStore) = makeOrchestrator()
        // Arm initial locator seen
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.0))
        // Set a pending stamp as if a sync cycle adopted a server timestamp
        orchestrator.setPendingServerJumpStamp(999L)
        orchestrator.onPositionChanged(buildLocator("ch1.html", 0.5))
        assertTrue("Stamp should be recorded", fakeStore.savedTimestamps.any { it.first == 999L })
    }

    /** (10) armReturnRestore re-fires server locator on spurious chapter-top clobber */
    @Test
    fun `armReturnRestore re-fires server locator on spurious chapter-top clobber`() = runTest(testDispatcher) {
        val (orchestrator, _, _) = makeOrchestrator()
        val originLocator = buildLocator("ch10.html", 0.5)
        // Arm the restore
        orchestrator.armReturnRestore(originLocator)

        val received = mutableListOf<Locator>()
        val job = launch { orchestrator.serverLocatorEvents.collect { received.add(it) } }

        // Emit a spurious chapter-top (progression = 0, below origin - 0.01 = 0.49)
        orchestrator.onPositionChanged(buildLocator("ch10.html", 0.0))

        // Should have re-fired the origin locator (progression >= 0.5)
        assertTrue(
            "Re-fire expected on spurious clobber; received=$received",
            received.any { it.href.toString() == "ch10.html" && (it.locations.progression ?: 0.0) >= 0.5 }
        )
        job.cancel()
    }
}
