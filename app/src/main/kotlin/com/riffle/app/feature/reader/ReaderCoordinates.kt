package com.riffle.app.feature.reader

import android.graphics.RectF
import android.view.View
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal fun RectF.toWindowIntRect(viewLeft: Int, viewTop: Int): IntRect =
    toWindowIntRect(left, top, right, bottom, viewLeft, viewTop)

internal fun RectF.toWindowIntRect(view: View): IntRect {
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    return toWindowIntRect(loc[0], loc[1])
}

internal fun toWindowIntRect(
    rectLeft: Float, rectTop: Float, rectRight: Float, rectBottom: Float,
    viewLeft: Int, viewTop: Int,
): IntRect = IntRect(
    left   = viewLeft + rectLeft.roundToInt(),
    top    = viewTop  + rectTop.roundToInt(),
    right  = viewLeft + rectRight.roundToInt(),
    bottom = viewTop  + rectBottom.roundToInt(),
)

/**
 * Returns the best anchor rect for the annotation popup in Readium (paginated/vertical) mode.
 *
 * [lastActionModeWindowRect] — if non-null, the action-mode toolbar's content rect already
 * converted to window/screen coordinates. This is correct for both paginated and vertical/scroll
 * modes because the action-mode interception layer already applies the right coordinate path:
 * CSS rect × DPR for paginated, Readium's native scroll-aware callback for vertical. Using the
 * same rect for the annotation popup gives it the same position as the toolbar anchor.
 *
 * [selectionRect] + ([viewLeft], [viewTop]) — fallback when no action-mode rect is available
 * (e.g. the action mode was dismissed before onGetContentRect fired). In paginated mode this path
 * is correct; in vertical/scroll mode it may place the popup off-screen but that is the
 * pre-existing behaviour, not a regression.
 */
internal fun resolveReadiumAnnotationAnchorRect(
    lastActionModeWindowRect: IntRect?,
    selectionRect: RectF?,
    viewLeft: Int,
    viewTop: Int,
): IntRect? = lastActionModeWindowRect
    ?: selectionRect?.let { r -> toWindowIntRect(r.left, r.top, r.right, r.bottom, viewLeft, viewTop) }

internal class HighlightPopupPositionProvider(
    private val anchorRect: IntRect,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // anchorBounds (Compose's composable anchor) is intentionally ignored — anchorRect is
        // already mapped to window coordinates from the WebView's coordinate space.
        val preferredTop = anchorRect.top - popupContentSize.height - margin
        val maxTop = windowSize.height - popupContentSize.height - margin
        val top = if (preferredTop >= margin) {
            preferredTop.coerceAtMost(maxTop)
        } else {
            (anchorRect.bottom + margin).coerceAtMost(maxTop)
        }
        val centreX = anchorRect.center.x - popupContentSize.width / 2
        val maxLeft = maxOf(margin, windowSize.width - popupContentSize.width - margin)
        val left = centreX.coerceIn(margin, maxLeft)
        return IntOffset(left, top)
    }
}
