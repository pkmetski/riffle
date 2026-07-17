package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.Clock
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.EmbeddedFigure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

class AnnotationStoreImpl(
    private val dao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) : AnnotationStore {

    @Inject
    constructor(dao: AnnotationDao, deviceIdStore: DeviceIdStore, clock: Clock) : this(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = clock::nowMs,
        idGenerator = { UUID.randomUUID().toString() },
    )

    override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeForItem(sourceId, itemId).map { rows ->
            rows.filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT }.map { it.toDomain() }
        }

    override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeForItem(sourceId, itemId).map { rows ->
            rows.filter { it.type == AnnotationEntity.TYPE_BOOKMARK }.map { it.toDomain() }
        }

    override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeAnnotationsByPosition(sourceId, itemId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> =
        dao.observeForSource(sourceId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createHighlight(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String,
        textAfter: String,
        color: String,
        spineIndex: Int,
        progression: Double,
        embeddedFigures: List<EmbeddedFigure>?,
        originFontFamily: String,
    ): Annotation {
        require(originFontFamily.isNotBlank()) {
            "originFontFamily must be non-blank for locally-created highlights (issue #484)"
        }
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            sourceId = sourceId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = cfi,
            color = color,
            note = null,
            textSnippet = textSnippet,
            textBefore = textBefore,
            textAfter = textAfter,
            chapterHref = chapterHref,
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = "",
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
            embeddedFigures = embeddedFigures.toEntityJson(),
            originFontFamily = originFontFamily,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun createBookmark(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
        originFontFamily: String,
    ): Annotation {
        require(originFontFamily.isNotBlank()) {
            "originFontFamily must be non-blank for locally-created bookmarks (issue #484)"
        }
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            sourceId = sourceId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = cfi,
            color = "",
            note = null,
            textSnippet = textSnippet,
            chapterHref = chapterHref,
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = bookmarkTitle,
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
            originFontFamily = originFontFamily,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun createImageAnnotation(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        imageHref: String?,
        imageSvg: String?,
        imageBytes: String?,
        color: String,
    ): Annotation {
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            sourceId = sourceId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = cfi,
            color = color,
            note = null,
            textSnippet = textSnippet,
            chapterHref = chapterHref,
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = "",
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
            embeddedFigures = null,
            imageHref = imageHref,
            imageSvg = imageSvg,
            imageBytes = imageBytes,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun backfillNullOriginFontFamily(
        sourceId: String,
        itemId: String,
        fontFamily: String,
    ): Int {
        if (fontFamily.isBlank()) return 0
        return dao.backfillNullOriginFontFamily(
            sourceId = sourceId,
            itemId = itemId,
            fontFamily = fontFamily,
            updatedAt = clock(),
            deviceId = deviceIdStore.getOrCreate(),
        )
    }

    override suspend fun healSentinelOriginFontFamily(
        sourceId: String,
        itemId: String,
        sentinel: String,
        fontFamily: String,
    ): Int {
        if (fontFamily.isBlank() || sentinel.isBlank() || fontFamily == sentinel) return 0
        return dao.healSentinelOriginFontFamily(
            sourceId = sourceId,
            itemId = itemId,
            sentinel = sentinel,
            fontFamily = fontFamily,
            updatedAt = clock(),
            deviceId = deviceIdStore.getOrCreate(),
        )
    }

    override suspend fun upgradeImageToCaptionHighlight(
        id: String,
        cfi: String,
        textSnippet: String,
        textBefore: String,
        textAfter: String,
        figure: EmbeddedFigure,
    ): Annotation? {
        val existing = dao.getById(id) ?: return null
        if (existing.deleted || existing.type != AnnotationEntity.TYPE_IMAGE) return null
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val upgraded = existing.copy(
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = cfi,
            textSnippet = textSnippet,
            textBefore = textBefore,
            textAfter = textAfter,
            embeddedFigures = listOf(figure).toEntityJson(),
            imageHref = null,
            imageSvg = null,
            imageBytes = null,
            updatedAt = now,
            lastModifiedByDeviceId = deviceId,
        )
        dao.upsert(upgraded)
        return upgraded.toDomain()
    }

    override suspend fun mergeFiguresIntoHighlight(
        id: String,
        newFigures: List<EmbeddedFigure>,
    ): Annotation? {
        if (newFigures.isEmpty()) return null
        val existing = dao.getById(id) ?: return null
        if (existing.deleted || existing.type != AnnotationEntity.TYPE_HIGHLIGHT) return null
        val current = existing.embeddedFigures.toEmbeddedFigures().orEmpty()
        // Union each incoming figure into the current list. When keys (href-filename or
        // svg-prefix) already match, PROMOTE non-null bytes/svg/caption from the incoming
        // figure onto the matching current one — a canonical row that was picked over a
        // duplicate whose figure had imageBytes must not silently drop those bytes just because
        // the canonical's own figure came in bytes-less. When keys don't match, append the
        // incoming figure with a fresh order index.
        val merged = current.toMutableList()
        var changed = false
        for (incoming in newFigures) {
            val incomingHrefKey = incoming.href?.let(::figureKeyFromHref)
            val incomingSvgKey = incoming.svg?.take(200)
            val matchIndex = merged.indexOfFirst { existingFig ->
                (incomingHrefKey != null && existingFig.href?.let(::figureKeyFromHref) == incomingHrefKey) ||
                    (incomingSvgKey != null && existingFig.svg?.take(200) == incomingSvgKey)
            }
            if (matchIndex >= 0) {
                val existingFig = merged[matchIndex]
                val promoted = existingFig.copy(
                    href = existingFig.href ?: incoming.href,
                    svg = existingFig.svg ?: incoming.svg,
                    caption = if (existingFig.caption.isBlank()) incoming.caption else existingFig.caption,
                    imageBytes = existingFig.imageBytes ?: incoming.imageBytes,
                    charOffset = existingFig.charOffset ?: incoming.charOffset,
                )
                if (promoted != existingFig) {
                    merged[matchIndex] = promoted
                    changed = true
                }
            } else {
                merged += incoming.copy(order = merged.size)
                changed = true
            }
        }
        if (!changed) return existing.toDomain()
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val updated = existing.copy(
            embeddedFigures = merged.toEntityJson(),
            updatedAt = now,
            lastModifiedByDeviceId = deviceId,
        )
        dao.upsert(updated)
        return updated.toDomain()
    }

    override suspend fun delete(id: String) {
        dao.tombstone(id, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun recolor(id: String, color: String) {
        dao.recolor(id, color = color, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun updateNote(id: String, note: String?) {
        dao.updateNote(id, note = note, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun renameBookmark(id: String, title: String) {
        dao.renameBookmark(id, title = title, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? =
        dao.getByItemAndCfi(sourceId, itemId, cfi)?.toDomain()

    override suspend fun findImageAnnotationForFigure(
        sourceId: String,
        itemId: String,
        chapterHref: String,
        imageHref: String?,
        imageSvg: String?,
    ): Annotation? =
        dao.findImageForFigure(sourceId, itemId, chapterHref, imageHref, imageSvg)?.toDomain()
}

/**
 * Filename-suffix key for an image href, matching how the reader dedupes figures across the
 * store/decoration/mergeEnclosedFigures paths (last path segment, stripped of query/fragment).
 * Inlined here — the app-module `figureHrefFilename` isn't visible from core/data.
 */
private fun figureKeyFromHref(href: String): String {
    val trimmed = href.substringBefore('?').substringBefore('#')
    val slash = trimmed.lastIndexOf('/')
    return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
}

private val embeddedFiguresJson = Json { ignoreUnknownKeys = true }
private val embeddedFiguresSerializer = ListSerializer(EmbeddedFigure.serializer())

/** Serializes to the entity's JSON column. Null and empty lists both map to a null column. */
private fun List<EmbeddedFigure>?.toEntityJson(): String? =
    if (this.isNullOrEmpty()) null else embeddedFiguresJson.encodeToString(embeddedFiguresSerializer, this)

/** Parses the entity's JSON column back into domain figures, ordered by [EmbeddedFigure.order]. */
private fun String?.toEmbeddedFigures(): List<EmbeddedFigure>? =
    this?.let { embeddedFiguresJson.decodeFromString(embeddedFiguresSerializer, it) }
        ?.sortedBy { it.order }

/**
 * Domain-to-entity mapper. Production write paths ([AnnotationStoreImpl.createHighlight] etc.)
 * build [AnnotationEntity] directly because they own device/clock provenance that [Annotation]
 * doesn't carry; this extension exists so mapper round-trip tests can exercise the
 * [EmbeddedFigure] / [imageHref] / [imageSvg] JSON codec symmetrically with [toDomain] without
 * duplicating the field list. `originDeviceId` / `lastModifiedByDeviceId` are not part of the
 * domain model, so they're left blank here — callers that need real provenance must not use this.
 */
internal fun Annotation.toEntity() = AnnotationEntity(
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
)

internal fun AnnotationEntity.toDomain() = Annotation(
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
    embeddedFigures = embeddedFigures.toEmbeddedFigures(),
    imageHref = imageHref,
    imageSvg = imageSvg,
    imageBytes = imageBytes,
    originFontFamily = originFontFamily,
)
