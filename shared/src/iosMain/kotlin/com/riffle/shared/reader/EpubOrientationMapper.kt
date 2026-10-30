package com.riffle.shared.reader

import com.riffle.core.domain.ReaderOrientation
import com.riffle.feature.reader.highlights.ReaderSource

fun epubScrollMode(orientation: ReaderOrientation): Boolean =
    orientation != ReaderOrientation.Horizontal

// Highlights mode must never use IosContinuousScrollView: that view has no decidePolicyFor
// delegate, so riffle://annotation-tap URLs are silently dropped and AnnotationActionsSheet
// never opens. Force EPUBNavigatorViewController (false) so presentExternalURL fires.
fun elidedAwareIsContinuousMode(source: ReaderSource, orientation: ReaderOrientation): Boolean =
    source != ReaderSource.Highlights && orientation == ReaderOrientation.Continuous

fun elidedAwareScrollMode(source: ReaderSource, orientation: ReaderOrientation): Boolean =
    if (source == ReaderSource.Highlights) true else epubScrollMode(orientation)
