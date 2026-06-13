package com.riffle.app.feature.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ReturnNavigator is generic over the position type so it stays free of Readium's Locator (which can't
// be built in a JVM unit test — it needs android.net.Uri). String stand-ins exercise the same logic the
// reader relies on with a real Locator.
@OptIn(ExperimentalCoroutinesApi::class)
class ReturnNavigatorTest {

    @Test
    fun `capture exposes the origin as the target`() = runTest(UnconfinedTestDispatcher()) {
        val nav = ReturnNavigator<String>()
        assertNull("no target before any capture", nav.target.value)

        nav.capture("chapter12@0.4")

        assertEquals("chapter12@0.4", nav.target.value)
    }

    @Test
    fun `a second capture replaces the first (single-level)`() = runTest(UnconfinedTestDispatcher()) {
        val nav = ReturnNavigator<String>()
        nav.capture("chapter12@0.4")
        nav.capture("chapter4@0.1")

        assertEquals("chapter4@0.1", nav.target.value)
    }

    @Test
    fun `returnToOrigin emits the captured origin and clears the target`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = ReturnNavigator<String>()
            val emitted = mutableListOf<String>()
            backgroundScope.launch { nav.navEvents.toList(emitted) }

            nav.capture("chapter12@0.4")
            nav.returnToOrigin()

            assertEquals(listOf("chapter12@0.4"), emitted)
            assertNull("target cleared after returning", nav.target.value)
        }

    @Test
    fun `returnToOrigin with no captured target is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val nav = ReturnNavigator<String>()
        val emitted = mutableListOf<String>()
        backgroundScope.launch { nav.navEvents.toList(emitted) }

        nav.returnToOrigin()

        assertNull(nav.target.value)
        assertEquals("no navigation emitted", 0, emitted.size)
    }

    @Test
    fun `dismiss clears the target without emitting a navigation`() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = ReturnNavigator<String>()
            val emitted = mutableListOf<String>()
            backgroundScope.launch { nav.navEvents.toList(emitted) }

            nav.capture("chapter12@0.4")
            nav.dismiss()

            assertNull("target cleared", nav.target.value)
            assertEquals("dismiss emits no navigation", 0, emitted.size)
        }
}
