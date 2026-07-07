package com.riffle.core.domain

data class Annotation(
    val id: String,
    val sourceId: String,
    val itemId: String,
    val type: String,
    val cfi: String,
    val color: String,
    val note: String?,
    val textSnippet: String,
    val textBefore: String,
    val textAfter: String,
    val chapterHref: String,
    val spineIndex: Int,
    val progression: Double,
    val bookmarkTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
)
