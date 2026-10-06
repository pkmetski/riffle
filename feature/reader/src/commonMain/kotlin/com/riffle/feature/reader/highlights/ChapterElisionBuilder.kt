package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Groups [rows] into [ChapterElision]s suitable for the elided Annotations View.
 *
 * Only highlights and image annotations that are not soft-deleted are included; bookmarks and
 * other types are silently skipped. The result preserves the first-encountered chapter order
 * (insertion into a [LinkedHashMap]) and within each chapter the highlights are ordered by
 * (spineIndex, progression, createdAt) — the stable order [ElidedChapterHtmlBuilder.renderChapterHtml]
 * expects.
 */
fun buildChapterElisions(rows: List<AnnotationEntity>): List<ChapterElision> {
    val live = rows
        .filter { (it.type == AnnotationEntity.TYPE_HIGHLIGHT || it.type == AnnotationEntity.TYPE_IMAGE) && !it.deleted }
        .sortedWith(compareBy({ it.spineIndex }, { it.progression }, { it.createdAt }))

    val byHref = LinkedHashMap<String, MutableList<AnnotationEntity>>()
    for (row in live) {
        byHref.getOrPut(row.chapterHref) { mutableListOf() }.add(row)
    }

    return byHref.entries.map { (href, highlights) ->
        ChapterElision(
            href = href,
            title = deriveChapterTitle(href),
            highlights = highlights,
        )
    }
}

/**
 * Groups [annotations] (domain model, already filtered for non-deleted) into [ChapterElision]s.
 *
 * Used by the iOS path where [com.riffle.core.domain.AnnotationStore] already returns non-deleted
 * highlights; the Android path uses [buildChapterElisions] which takes the raw [AnnotationEntity]
 * and applies the soft-delete filter itself.
 */
fun buildChapterElisionsFromAnnotations(annotations: List<Annotation>): List<ChapterElision> {
    val live = annotations
        .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT || it.type == AnnotationEntity.TYPE_IMAGE }
        .sortedWith(compareBy({ it.spineIndex }, { it.progression }, { it.createdAt }))

    val byHref = LinkedHashMap<String, MutableList<AnnotationEntity>>()
    for (ann in live) {
        byHref.getOrPut(ann.chapterHref) { mutableListOf() }.add(ann.toAnnotationEntity())
    }

    return byHref.entries.map { (href, highlights) ->
        ChapterElision(
            href = href,
            title = deriveChapterTitle(href),
            highlights = highlights,
        )
    }
}

private fun Annotation.toAnnotationEntity(): AnnotationEntity = AnnotationEntity(
    id = id,
    sourceId = sourceId,
    itemId = itemId,
    type = type,
    cfi = cfi,
    color = color,
    note = note,
    textSnippet = textSnippet,
    textBefore = textBefore,
    textAfter = textAfter,
    chapterHref = chapterHref,
    spineIndex = spineIndex,
    progression = progression,
    bookmarkTitle = bookmarkTitle,
    createdAt = createdAt,
    updatedAt = updatedAt,
    originDeviceId = "",
    lastModifiedByDeviceId = "",
    embeddedFigures = embeddedFigures.toEntityJson(),
    imageHref = imageHref,
    imageSvg = imageSvg,
    imageBytes = imageBytes,
    originFontFamily = originFontFamily,
    emphasisStyles = EmphasisStyle.encode(emphasisStyles.orEmpty()),
    textSnippetHtml = textSnippetHtml,
    fragmentAnchor = fragmentAnchor,
)

private fun List<EmbeddedFigure>?.toEntityJson(): String? =
    this?.let { Json.encodeToString(ListSerializer(EmbeddedFigure.serializer()), it) }

/**
 * Derives a human-readable chapter title from a spine href.
 *
 * Strips the path prefix and file extension from [href], returning the bare filename.
 * Returns `"Chapter"` for blank results. This is the fallback title used when no real TOC
 * entry is available for the href; Android's `elidedChapterTitle` can upgrade it with TOC data.
 */
fun deriveChapterTitle(href: String): String {
    val name = href.substringAfterLast('/').substringBeforeLast('.')
    return name.ifBlank { "Chapter" }
}
