package com.riffle.feature.settings

import com.riffle.core.domain.comic.PanelOverflowBehavior

/**
 * The comic panel-overflow choices as the settings UI presents them: order, label and the
 * one-line description that tells the reader what each does.
 *
 * Android's `PanelOverflowRadioGroup` listed them OFF → SPLIT → SMART_SPLIT with a description
 * under each; iOS's chip row listed them SPLIT → SMART_SPLIT → OFF and dropped the descriptions
 * entirely, so the same three settings read as a different control on each platform. The order
 * and the text now live here once.
 *
 * Each string mirrors the `values/strings.xml` entry named in its comment;
 * `PanelOverflowStringsParityTest` in `:app` fails if the two diverge.
 */
object PanelOverflowOptions {

    /** Presentation order — "No split" first, because it is the least surprising choice. */
    val ORDER: List<PanelOverflowBehavior> = listOf(
        PanelOverflowBehavior.OFF,
        PanelOverflowBehavior.SPLIT,
        PanelOverflowBehavior.SMART_SPLIT,
    )

    fun label(behavior: PanelOverflowBehavior): String = when (behavior) {
        // R.string.ui_no_split
        PanelOverflowBehavior.OFF -> "No split"
        // R.string.ui_split
        PanelOverflowBehavior.SPLIT -> "Split"
        // R.string.ui_smart_split
        PanelOverflowBehavior.SMART_SPLIT -> "Smart split"
    }

    fun description(behavior: PanelOverflowBehavior): String = when (behavior) {
        // R.string.ui_show_oversized_panels_as_is_without_splitting
        PanelOverflowBehavior.OFF -> "Show oversized panels as-is without splitting"
        // R.string.ui_cuts_oversized_panels_in_half
        PanelOverflowBehavior.SPLIT -> "Cuts oversized panels in half and shows each half as its own page"
        // R.string.ui_smart_split_description
        PanelOverflowBehavior.SMART_SPLIT ->
            "Like Split, but finds a natural seam (gutter or whitespace) to cut at a cleaner boundary"
    }
}
