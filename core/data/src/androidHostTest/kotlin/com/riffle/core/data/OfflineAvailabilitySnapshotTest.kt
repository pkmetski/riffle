package com.riffle.core.data

import com.riffle.core.data.testing.TestApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineAvailabilitySnapshotTest {

    @Test
    fun `reads return initial until the first upstream emission`() = runTest {
        val snapshot = OfflineAvailabilitySnapshot<String, Int>(
            applicationScope = TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler))),
            source = emptyFlow(),
            initial = mapOf("a" to 1),
        )
        assertEquals(1, snapshot["a"])
        assertEquals(mapOf("a" to 1), snapshot.snapshot())
        assertNull(snapshot["missing"])
    }

    @Test
    fun `reads return the latest upstream emission`() = runTest {
        val source = MutableStateFlow(mapOf("a" to 1))
        val appScope = TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        try {
            val snapshot = OfflineAvailabilitySnapshot(appScope, source)
            assertEquals(1, snapshot["a"])

            source.value = mapOf("a" to 2, "b" to 9)
            assertEquals(2, snapshot["a"])
            assertEquals(9, snapshot["b"])

            source.value = emptyMap()
            assertNull(snapshot["a"])
            assertEquals(emptyMap<String, Int>(), snapshot.snapshot())
        } finally {
            appScope.coroutineScope.cancel()
        }
    }

    @Test
    fun `snapshot returns the same map instance the upstream produced`() = runTest {
        val emitted = mapOf("a" to 1, "b" to 2)
        val source = MutableStateFlow(emitted)
        val appScope = TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        try {
            val snapshot = OfflineAvailabilitySnapshot(appScope, source)
            assertEquals(emitted, snapshot.snapshot())
        } finally {
            appScope.coroutineScope.cancel()
        }
    }
}
