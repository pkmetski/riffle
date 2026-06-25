package com.riffle.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSyncStatusStoreTest {

    @Test
    fun `initial outcome is NeverRun`() {
        val store = AnnotationSyncStatusStore()
        assertEquals(CycleOutcome.NeverRun, store.lastCycleOutcome.value)
    }

    @Test
    fun `report Success updates outcome`() = runTest {
        val store = AnnotationSyncStatusStore()
        store.report(CycleOutcome.Success(1_234L))
        val v = store.lastCycleOutcome.value
        assertTrue(v is CycleOutcome.Success)
        assertEquals(1_234L, (v as CycleOutcome.Success).atMs)
    }

    @Test
    fun `report Failed Network preserves category`() {
        val store = AnnotationSyncStatusStore()
        store.report(CycleOutcome.Failed.Network(5L, "timeout"))
        val v = store.lastCycleOutcome.value
        assertTrue(v is CycleOutcome.Failed.Network)
        assertEquals(5L, (v as CycleOutcome.Failed.Network).atMs)
        assertEquals("timeout", v.message)
    }

    @Test
    fun `subsequent reports overwrite the previous outcome`() {
        val store = AnnotationSyncStatusStore()
        store.report(CycleOutcome.Failed.Auth(1L, 401))
        store.report(CycleOutcome.Success(2L))
        assertTrue(store.lastCycleOutcome.value is CycleOutcome.Success)
    }
}
