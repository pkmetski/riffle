package com.riffle.shared.reader

import com.riffle.core.domain.ReaderOrientation
import com.riffle.feature.reader.highlights.ReaderSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [elidedAwareIsContinuousMode] and [elidedAwareScrollMode].
 *
 * Root cause: when the user's reading mode is Continuous, [IosContinuousScrollView] was
 * activated for the Highlights-mode synthetic EPUB. [IosContinuousScrollView] has no
 * `decidePolicyFor` navigation delegate and therefore no `riffle://` URL interceptor.
 * Tapping an annotation accent bar set `location.href = 'riffle://annotation-tap/<id>'`
 * in the WebView, which was silently swallowed — [AnnotationActionsSheet] never opened and
 * the "Go to book" / options menu was never shown.
 *
 * Fix: [elidedAwareIsContinuousMode] returns `false` for [ReaderSource.Highlights] regardless
 * of the user's orientation preference, so [EPUBNavigatorViewController] is always used and
 * [presentExternalURL] fires on every accent-bar tap.
 */
class ElidedViewModeTest {

    // ---- isContinuousMode -------------------------------------------------------------------

    @Test
    fun isContinuousMode_highlightsContinuous_isFalse() {
        assertFalse(
            elidedAwareIsContinuousMode(ReaderSource.Highlights, ReaderOrientation.Continuous),
            "Highlights mode must never activate IosContinuousScrollView — riffle:// URLs need presentExternalURL",
        )
    }

    @Test
    fun isContinuousMode_highlightsHorizontal_isFalse() {
        assertFalse(elidedAwareIsContinuousMode(ReaderSource.Highlights, ReaderOrientation.Horizontal))
    }

    @Test
    fun isContinuousMode_highlightsVertical_isFalse() {
        assertFalse(elidedAwareIsContinuousMode(ReaderSource.Highlights, ReaderOrientation.Vertical))
    }

    @Test
    fun isContinuousMode_fullBookContinuous_isTrue() {
        assertTrue(elidedAwareIsContinuousMode(ReaderSource.FullBook, ReaderOrientation.Continuous))
    }

    @Test
    fun isContinuousMode_fullBookVertical_isFalse() {
        assertFalse(elidedAwareIsContinuousMode(ReaderSource.FullBook, ReaderOrientation.Vertical))
    }

    // ---- scrollMode -------------------------------------------------------------------------

    @Test
    fun scrollMode_highlightsContinuous_isTrue() {
        assertTrue(elidedAwareScrollMode(ReaderSource.Highlights, ReaderOrientation.Continuous))
    }

    @Test
    fun scrollMode_highlightsHorizontal_isTrue() {
        assertTrue(
            elidedAwareScrollMode(ReaderSource.Highlights, ReaderOrientation.Horizontal),
            "Highlights mode must always scroll vertically, even when user prefers paginated columns",
        )
    }

    @Test
    fun scrollMode_highlightsVertical_isTrue() {
        assertTrue(elidedAwareScrollMode(ReaderSource.Highlights, ReaderOrientation.Vertical))
    }

    @Test
    fun scrollMode_fullBookHorizontal_isFalse() {
        assertFalse(elidedAwareScrollMode(ReaderSource.FullBook, ReaderOrientation.Horizontal))
    }

    @Test
    fun scrollMode_fullBookVertical_isTrue() {
        assertTrue(elidedAwareScrollMode(ReaderSource.FullBook, ReaderOrientation.Vertical))
    }

    @Test
    fun scrollMode_fullBookContinuous_isTrue() {
        assertTrue(elidedAwareScrollMode(ReaderSource.FullBook, ReaderOrientation.Continuous))
    }
}
