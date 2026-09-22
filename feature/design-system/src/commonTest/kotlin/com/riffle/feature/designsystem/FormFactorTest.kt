package com.riffle.feature.designsystem

import androidx.compose.ui.unit.dp
import com.riffle.feature.library.CoverGridLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS had no form-factor handling at all: four literal `isExpandedWidth = false` call sites in
 * `SourceOnboardingHost`, so an iPad rendered the phone layout. These pin the predicate that
 * replaced them, on the iOS code path (`commonTest` runs on `iosSimulatorArm64Test`).
 */
class FormFactorTest {

    @Test
    fun phoneWidthIsNotExpanded() {
        // 393dp — iPhone 15 portrait.
        assertFalse(isExpandedWidth(393f))
    }

    @Test
    fun mediumWidthIsNotExpanded() {
        // 744dp — iPad mini portrait sits in the Medium class, below the Tablet Layout.
        assertFalse(isExpandedWidth(744f))
    }

    @Test
    fun breakpointIsInclusive() {
        assertFalse(isExpandedWidth(CoverGridLayout.EXPANDED_WIDTH_BREAKPOINT_DP - 1f))
        assertTrue(isExpandedWidth(CoverGridLayout.EXPANDED_WIDTH_BREAKPOINT_DP))
    }

    @Test
    fun ipadLandscapeIsExpanded() {
        // 1024dp — 12.9" iPad Pro portrait / 11" landscape.
        assertTrue(isExpandedWidth(1024f))
    }

    @Test
    fun dpOverloadAgreesWithFloatOverload() {
        assertEquals(isExpandedWidth(900f), isExpandedWidth(900.dp))
        assertEquals(isExpandedWidth(600f), isExpandedWidth(600.dp))
    }

    @Test
    fun breakpointIsTheAdr0019ExpandedClass() {
        // Android derives the same predicate from WindowSizeClass.Expanded; if this constant ever
        // drifts the two platforms silently disagree about what a tablet is.
        assertEquals(840f, CoverGridLayout.EXPANDED_WIDTH_BREAKPOINT_DP)
    }
}
