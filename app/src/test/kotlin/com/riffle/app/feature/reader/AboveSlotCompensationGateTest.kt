package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sequencing behind #1109: an annotation landing requested while an above slot's scroll
 * compensation is still waiting for layout must run AFTER that compensation, not before it and
 * not never. Reverting the gate in `ContinuousWindowController.landOnAnnotationOffset` to the
 * old compute-then-post shape lands the reader short by the slot's height delta on CI's
 * slower emulator (see `AnnotationFocusHarnessTest.continuousMode_annotationTap_focusesAnnotationOnScreen`).
 */
class AboveSlotCompensationGateTest {

    @Test
    fun runsImmediatelyWhenNothingIsPending() {
        val gate = AboveSlotCompensationGate()
        val runs = mutableListOf<String>()

        gate.runWhenSettled { runs += "land" }

        assertEquals(listOf("land"), runs)
    }

    @Test
    fun holdsTheLandingUntilThePendingCompensationEnds() {
        val gate = AboveSlotCompensationGate()
        val runs = mutableListOf<String>()
        val slot = Any()

        gate.begin(slot)
        gate.runWhenSettled { runs += "land" }
        assertEquals("must not scroll against stale slot tops", emptyList<String>(), runs)

        gate.end(slot)
        assertEquals(listOf("land"), runs)
    }

    @Test
    fun waitsForEveryPendingSlotAndKeepsOnlyTheLatestRequest() {
        val gate = AboveSlotCompensationGate()
        val runs = mutableListOf<String>()
        val a = Any()
        val b = Any()

        gate.begin(a)
        gate.begin(b)
        gate.runWhenSettled { runs += "stale" }
        gate.runWhenSettled { runs += "latest" }
        gate.end(a)
        assertEquals("one slot still pending", emptyList<String>(), runs)

        gate.end(b)
        assertEquals("older request was superseded", listOf("latest"), runs)
    }

    @Test
    fun endingAnEvictedOrUnknownSlotIsIdempotentAndCannotWedgeTheGate() {
        val gate = AboveSlotCompensationGate()
        val runs = mutableListOf<String>()
        val evicted = Any()

        gate.begin(evicted)
        gate.runWhenSettled { runs += "land" }
        gate.end(Any()) // unrelated key: still pending
        assertEquals(emptyList<String>(), runs)
        gate.end(evicted) // eviction path releases it
        gate.end(evicted) // a late doOnNextLayout for the same view is a no-op
        assertEquals(listOf("land"), runs)
        assertEquals(0, gate.pending)
    }

    @Test
    fun cancelDeferredDropsTheHeldLandingButKeepsThePendingSet() {
        val gate = AboveSlotCompensationGate()
        val runs = mutableListOf<String>()
        val slot = Any()

        gate.begin(slot)
        gate.runWhenSettled { runs += "land" }
        gate.cancelDeferred()
        gate.end(slot)

        assertEquals("user took over: the stale landing must not fire later", emptyList<String>(), runs)
    }

    @Test
    fun resetForgetsPendingSlotsAndHeldRequest() {
        val gate = AboveSlotCompensationGate()
        val runs = mutableListOf<String>()

        gate.begin(Any())
        gate.runWhenSettled { runs += "old" }
        gate.reset()
        gate.runWhenSettled { runs += "new" }

        assertEquals(listOf("new"), runs)
        assertEquals(0, gate.pending)
    }
}
