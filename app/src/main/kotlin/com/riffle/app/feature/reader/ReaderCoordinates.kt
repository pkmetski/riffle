package com.riffle.app.feature.reader

import android.graphics.RectF
import android.view.View
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

internal fun RectF.toWindowIntRect(viewLeft: Int, viewTop: Int): IntRect = IntRect(
    left   = viewLeft + left.roundToInt(),
    top    = viewTop  + top.roundToInt(),
    right  = viewLeft + right.roundToInt(),
    bottom = viewTop  + bottom.roundToInt(),
)

internal fun RectF.toWindowIntRect(view: View): IntRect {
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    return toWindowIntRect(loc[0], loc[1])
}

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
        val top = if (preferredTop >= margin) preferredTop else anchorRect.bottom + margin
        val centreX = anchorRect.center.x - popupContentSize.width / 2
        val maxLeft = maxOf(margin, windowSize.width - popupContentSize.width - margin)
        val left = centreX.coerceIn(margin, maxLeft)
        return IntOffset(left, top)
    }
}
