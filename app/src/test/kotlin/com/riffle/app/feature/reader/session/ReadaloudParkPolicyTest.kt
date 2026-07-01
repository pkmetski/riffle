package com.riffle.app.feature.reader.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadaloudParkPolicyTest {

    @Test
    fun `onPause with null fragment leaves park unset`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause(pausedFragment = null, snapshotHref = "chap1", snapshotProgression = 0.25)
        assertNull(policy.fragmentRef)
    }

    @Test
    fun `onPause records fragment`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause(pausedFragment = "chap1#s10", snapshotHref = "chap1", snapshotProgression = 0.25)
        assertEquals("chap1#s10", policy.fragmentRef)
    }

    @Test
    fun `onClose with null fragment leaves fragmentRef null`() {
        val policy = ReadaloudParkPolicy()
        policy.onClose(resumeFragment = null, snapshotHref = "chap1", snapshotProgression = 0.25)
        // onClose accepts a null fragment (unlike onPause which early-returns): the caller
        // wanted to stamp the close, but with no active narration there is no park to reason
        // about. fragmentRef stays null and future onPosition calls are guarded no-ops.
        assertNull(policy.fragmentRef)
    }

    @Test
    fun `onClose with fragment records it`() {
        val policy = ReadaloudParkPolicy()
        policy.onClose(resumeFragment = "chap1#s10", snapshotHref = "chap1", snapshotProgression = 0.25)
        assertEquals("chap1#s10", policy.fragmentRef)
    }

    @Test
    fun `onPosition on same href within epsilon keeps park`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause("chap1#s10", "chap1", 0.25)
        policy.onPosition("chap1", 0.2505)  // within PARK_PAGE_EPS
        assertEquals("chap1#s10", policy.fragmentRef)
    }

    @Test
    fun `onPosition beyond epsilon clears park`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause("chap1#s10", "chap1", 0.25)
        policy.onPosition("chap1", 0.30)
        assertNull(policy.fragmentRef)
    }

    @Test
    fun `onPosition on different href clears park`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause("chap1#s10", "chap1", 0.25)
        policy.onPosition("chap2", 0.25)
        assertNull(policy.fragmentRef)
    }

    @Test
    fun `onPosition when not parked does nothing`() {
        val policy = ReadaloudParkPolicy()
        policy.onPosition("chap5", 0.9)
        assertNull(policy.fragmentRef)
    }

    @Test
    fun `null progression treated as zero for delta`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause("chap1#s10", "chap1", null)
        policy.onPosition("chap1", null)
        assertEquals("chap1#s10", policy.fragmentRef)
        policy.onPosition("chap1", 0.5)
        assertNull(policy.fragmentRef)
    }

    @Test
    fun `reset clears everything`() {
        val policy = ReadaloudParkPolicy()
        policy.onPause("chap1#s10", "chap1", 0.25)
        policy.reset()
        assertNull(policy.fragmentRef)
        // Post-reset, onPosition must not resurrect state.
        policy.onPosition("chap2", 0.9)
        assertNull(policy.fragmentRef)
    }
}
