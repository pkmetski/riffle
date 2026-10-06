package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity

/**
 * One chapter's worth of highlights to be rendered into the elided reader (ADR 0048).
 *
 * [highlights] must already be sorted by (spineIndex, progression, createdAt) — the builder
 * renders them in the order given, it does not re-sort.
 */
data class ChapterElision(
    val href: String,
    val title: String,
    val highlights: List<AnnotationEntity>,
)
