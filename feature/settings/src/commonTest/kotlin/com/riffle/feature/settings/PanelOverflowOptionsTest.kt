package com.riffle.feature.settings

import com.riffle.core.domain.comic.PanelOverflowBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the comic panel-overflow choice list.
 *
 * Android presented OFF → SPLIT → SMART_SPLIT with a description under each; iOS presented
 * SPLIT → SMART_SPLIT → OFF with none, so the same three settings read as a different control
 * depending on the device. Order and text are one table now.
 */
class PanelOverflowOptionsTest {

    @Test fun orderPutsNoSplitFirst() {
        assertEquals(
            listOf(
                PanelOverflowBehavior.OFF,
                PanelOverflowBehavior.SPLIT,
                PanelOverflowBehavior.SMART_SPLIT,
            ),
            PanelOverflowOptions.ORDER,
        )
    }

    @Test fun orderCoversEveryBehaviour() {
        assertEquals(PanelOverflowBehavior.entries.toSet(), PanelOverflowOptions.ORDER.toSet())
    }

    @Test fun labels() {
        assertEquals("No split", PanelOverflowOptions.label(PanelOverflowBehavior.OFF))
        assertEquals("Split", PanelOverflowOptions.label(PanelOverflowBehavior.SPLIT))
        assertEquals("Smart split", PanelOverflowOptions.label(PanelOverflowBehavior.SMART_SPLIT))
    }

    @Test fun everyBehaviourExplainsItself() {
        assertEquals(
            "Show oversized panels as-is without splitting",
            PanelOverflowOptions.description(PanelOverflowBehavior.OFF),
        )
        assertEquals(
            "Cuts oversized panels in half and shows each half as its own page",
            PanelOverflowOptions.description(PanelOverflowBehavior.SPLIT),
        )
        assertEquals(
            "Like Split, but finds a natural seam (gutter or whitespace) to cut at a cleaner boundary",
            PanelOverflowOptions.description(PanelOverflowBehavior.SMART_SPLIT),
        )
    }
}
