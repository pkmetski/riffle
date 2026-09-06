package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.presenter.FakeReaderPresenter
import com.riffle.app.feature.reader.presenter.ReaderPosition
import com.riffle.feature.reader.NavigatorPosition
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android counterpart of the iOS bridge callback tests in
 * `iosApp/iosAppTests/EpubReaderTests.swift` (`testLocatorCallbackIsInvokedAfterRegistration`,
 * `testClearingCallbacksStopsFiring`): once the navigator is closed, presenter emissions must
 * no longer reach consumers of [AndroidEpubNavigator]'s flows — the stale-callback guard for
 * a book that was closed and reopened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidEpubNavigatorCloseTest {

    private fun navigator() = AndroidEpubNavigator(
        assetRetriever = mockk(relaxed = true),
        publicationOpener = mockk(relaxed = true),
    )

    @Test
    fun `positionFlow delivers presenter positions while presenter is set`() = runTest {
        val navigator = navigator()
        val presenter = FakeReaderPresenter()
        navigator.setPresenter(presenter)

        val received = mutableListOf<NavigatorPosition>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            navigator.positionFlow.collect { received.add(it) }
        }

        presenter.emitPosition(
            ReaderPosition(
                href = "/ch1.xhtml",
                progression = 0.5f,
                totalProgression = 0.25f,
                locatorJson = """{"href":"/ch1.xhtml"}""",
            ),
        )

        assertEquals(1, received.size)
        assertEquals("/ch1.xhtml", received.single().href)
        assertEquals(0.5f, received.single().progression)
        job.cancel()
    }

    @Test
    fun `positionFlow obtained after close ignores presenter emissions`() = runTest {
        val navigator = navigator()
        val presenter = FakeReaderPresenter()
        navigator.setPresenter(presenter)
        navigator.close()

        val received = mutableListOf<NavigatorPosition>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            navigator.positionFlow.collect { received.add(it) }
        }

        presenter.emitPosition(
            ReaderPosition(
                href = "/ch1.xhtml",
                progression = 0.5f,
                totalProgression = null,
                locatorJson = "{}",
            ),
        )

        assertTrue(
            "No positions must reach a consumer after close(), got: $received",
            received.isEmpty(),
        )
        job.cancel()
    }

    @Test
    fun `event and page-load flows obtained after close ignore presenter emissions`() = runTest {
        val navigator = navigator()
        val presenter = FakeReaderPresenter()
        navigator.setPresenter(presenter)
        navigator.close()

        var events = 0
        var pageLoads = 0
        val eventJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            navigator.eventFlow.collect { events++ }
        }
        val pageLoadJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            navigator.pageLoadEvents.collect { pageLoads++ }
        }

        presenter.emitTap()
        presenter.emitPageLoad(1)

        assertEquals("No events must fire after close()", 0, events)
        assertEquals("No page loads must fire after close()", 0, pageLoads)
        eventJob.cancel()
        pageLoadJob.cancel()
    }
}
