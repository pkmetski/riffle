package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.panel.PagePanels

// Raw values matching android.content.pm.ActivityInfo constants, kept here
// so the pure logic is JVM-testable without Android stubs.
internal const val ORIENTATION_SENSOR_LANDSCAPE = 6
internal const val ORIENTATION_SENSOR_PORTRAIT = 7

/**
 * Returns the forced screen orientation for AUTO_ROTATE Panel Overflow, or null if no rotation
 * is needed. Depends only on panel/image dimensions — NOT on the current viewport size — to
 * avoid a feedback loop where forced rotation changes the viewport and retrips the condition.
 *
 * Wide panel (≥90% of image width) in any orientation → force landscape.
 * Tall panel (≥90% of image height) in any orientation → force portrait.
 */
internal fun computeAutoRotateOrientation(
    formatting: ComicFormattingPreferences,
    panelIndex: Int,
    pagePanels: PagePanels?,
): Int? {
    if (!formatting.panelViewOn) return null
    if (formatting.panelOverflow != PanelOverflowBehavior.AUTO_ROTATE) return null
    if (pagePanels == null || pagePanels.isFallback) return null
    val panel = pagePanels.panels.getOrNull(panelIndex) ?: return null
    val widthRatio = panel.width.toFloat() / pagePanels.imageWidth
    val heightRatio = panel.height.toFloat() / pagePanels.imageHeight
    return when {
        widthRatio >= 0.9f -> ORIENTATION_SENSOR_LANDSCAPE
        heightRatio >= 0.9f -> ORIENTATION_SENSOR_PORTRAIT
        else -> null
    }
}
