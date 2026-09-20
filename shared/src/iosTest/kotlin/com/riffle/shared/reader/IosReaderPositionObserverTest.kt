package com.riffle.shared.reader

import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.PositionSaveCoordinator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The defect: iOS persisted the reading position only from `onDispose`, so anything that skips
 * teardown — a force-quit, a crash, iOS reclaiming a backgrounded app — lost the whole session.
 * `PositionSaveCoordinator`, the shared policy Android has always used, had zero iOS callers.
 *
 * These assertions go red if the hot-path save is removed again: `saved` collapses to empty.
 */
class IosReaderPositionObserverTest {

    private fun position(href: String, progression: Float) = NavigatorPosition(
        href = href,
        progression = progression,
        totalProgression = progression,
        locatorJson = """{"href":"$href","locations":{"progression":$progression}}""",
    )

    private fun shape(vararg hrefs: String) = LazyPublicationShape(
        bookId = "book-1",
        identifier = "urn:test",
        title = "T",
        language = "en",
        spine = hrefs.mapIndexed { index, path ->
            LazySpineItem(
                index = index,
                fullPath = path,
                title = "Chapter $index",
                declaredByteSize = 1L,
                mediaType = "application/xhtml+xml",
            )
        },
        absoluteFilesPrefix = "https://host.test/files",
        pathFilesPrefix = "/files",
        cssFullPaths = emptyList(),
    )

    private class Recorder {
        val saved = mutableListOf<String>()
        val progress = mutableListOf<Float>()
        val coordinator = PositionSaveCoordinator<NavigatorPosition>(
            updateProgress = { progress.add(it) },
            savePosition = { saved.add(it.locatorJson) },
        )
    }

    @Test
    fun everyPositionChangeIsPersistedNotJustTheLast() = runTest {
        val recorder = Recorder()

        observeReaderPositions(
            positions = flowOf(
                position("ch1.xhtml", 0.1f),
                position("ch2.xhtml", 0.4f),
                position("ch3.xhtml", 0.9f),
            ),
            positionSaver = recorder.coordinator,
            lazyShape = { null },
            prefetchNext = {},
        )

        assertEquals(3, recorder.saved.size, "a force-quit after any change must still find that change on disk")
        assertEquals(position("ch3.xhtml", 0.9f).locatorJson, recorder.saved.last())
    }

    @Test
    fun theHotPathNeverWritesTheReadingProgressFloat() = runTest {
        val recorder = Recorder()

        observeReaderPositions(
            positions = flowOf(position("ch1.xhtml", 0.1f), position("ch1.xhtml", 0.2f)),
            positionSaver = recorder.coordinator,
            lazyShape = { null },
            prefetchNext = {},
        )

        assertEquals(
            emptyList(),
            recorder.progress,
            "readingProgress hits library_items and invalidates every library Flow — close only",
        )
    }

    @Test
    fun aLazyPublicationPrefetchesTheChapterAfterTheCurrentOne() = runTest {
        val recorder = Recorder()
        val prefetched = mutableListOf<Int>()

        observeReaderPositions(
            positions = flowOf(position("ch2.xhtml", 0.4f)),
            positionSaver = recorder.coordinator,
            lazyShape = { shape("ch1.xhtml", "ch2.xhtml", "ch3.xhtml") },
            prefetchNext = { prefetched.add(it) },
        )

        assertEquals(listOf(1), prefetched)
    }

    @Test
    fun anHrefOutsideTheSpineStillPersistsButDoesNotPrefetch() = runTest {
        val recorder = Recorder()
        val prefetched = mutableListOf<Int>()

        observeReaderPositions(
            positions = flowOf(position("cover.xhtml", 0.0f)),
            positionSaver = recorder.coordinator,
            lazyShape = { shape("ch1.xhtml", "ch2.xhtml") },
            prefetchNext = { prefetched.add(it) },
        )

        assertEquals(1, recorder.saved.size)
        assertEquals(emptyList(), prefetched)
    }

    @Test
    fun aNonLazyPublicationPersistsWithoutTouchingThePrefetcher() = runTest {
        val recorder = Recorder()
        var prefetchCalls = 0

        observeReaderPositions(
            positions = flowOf(position("ch1.xhtml", 0.5f)),
            positionSaver = recorder.coordinator,
            lazyShape = { null },
            prefetchNext = { prefetchCalls++ },
        )

        assertEquals(1, recorder.saved.size)
        assertEquals(0, prefetchCalls)
    }
}
