package com.riffle.feature.reader

import com.riffle.core.models.EmphasisStyle

/**
 * Platform-neutral screen rectangle for anchoring the annotation actions popup.
 *
 * Android converts from [androidx.compose.ui.unit.IntRect]; iOS passes a zero rect since it
 * renders the actions sheet as a bottom sheet anchored by the OS, not by position.
 */
data class AnchorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val Zero = AnchorRect(0, 0, 0, 0)
    }
}

/**
 * One rendered highlight segment — enough for the navigator to paint the decoration.
 *
 * [locatorJson] is a platform-neutral Readium locator JSON string ([LocatorJson]). Android
 * converts it to a Readium JVM [org.readium.r2.shared.publication.Locator] at the decoration
 * application site; iOS passes it directly to Readium Swift.
 */
data class HighlightRender(
    val id: String,
    val locatorJson: LocatorJson,
    val color: String,
    val note: String?,
    val useAccentBarStyle: Boolean = false,
    val emphasisStyles: Set<EmphasisStyle> = emptySet(),
    val isBeingEdited: Boolean = false,
)

/**
 * Which highlight's action sheet is currently open, and where it is anchored on screen.
 *
 * [anchorRect] is populated by Android to position the popup relative to the highlight; iOS
 * ignores it (the sheet is bottom-anchored by the OS). [noteOnly] shows only the note row.
 * [justCreatedFromDraft] gates the tombstone-on-empty garbage-collection in
 * [AnnotationSession.dismissHighlightActions].
 */
data class HighlightEditTarget(
    val id: String,
    val anchorRect: AnchorRect = AnchorRect.Zero,
    val noteOnly: Boolean = false,
    val justCreatedFromDraft: Boolean = false,
)
