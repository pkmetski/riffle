package com.riffle.core.domain

/**
 * A reader Annotation (Highlight in v1). Anchored on an ABS-EPUB CFI — a *range* for highlights —
 * and scoped to an ABS Library Item ([serverId] + [itemId]). See ADR 0024 / 0025.
 */
data class Annotation(
    val id: String,
    val serverId: String,
    val itemId: String,
    val type: String,
    val cfi: String,
    val color: String,
    val note: String?,
    val textSnippet: String,
    val textBefore: String,
    val textAfter: String,
    val chapterHref: String,
    val createdAt: Long,
    val updatedAt: Long,
)
