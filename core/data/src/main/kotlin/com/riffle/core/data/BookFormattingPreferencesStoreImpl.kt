package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SourceRepository
import javax.inject.Inject

// Formatting is per-device, keyed by (sourceId, itemId, screenDimensionBucket). sourceId
// prevents colliding item ids across Sources from sharing one row (ADR 0031); screenDimensionBucket
// gives each screen-size class independent settings for the same book. Both the full-book reader
// and the elided (annotations) reader share the same row.
class BookFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookFormattingPreferencesDao,
    private val sourceRepository: SourceRepository,
) : BookFormattingPreferencesStore {

    override suspend fun load(
        itemId: String,
        dimension: ScreenDimensionBucket,
    ): BookFormattingOverrides? {
        val sourceId = sourceRepository.getActive()?.id ?: return null
        val entity = dao.getByItemId(sourceId, itemId, dimension.encode()) ?: return null
        return BookFormattingOverrides(
            fontSize = entity.fontSize,
            theme = entity.theme?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() },
            fontFamily = entity.fontFamily?.decodeFontFamily(),
            lineSpacing = entity.lineSpacing,
            margins = entity.margins,
            orientation = entity.orientation?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() },
            showChapterMap = entity.showChapterMap,
            coloredChapterMap = entity.coloredChapterMap,
            showReadingProgressLabels = entity.showReadingProgressLabels,
            showCurrentChapterLabel = entity.showCurrentChapterLabel,
            doublePageSpread = entity.doublePageSpread,
            justifyText = entity.justifyText,
            showReadingTimeEstimate = entity.showReadingTimeEstimate,
        )
    }

    override suspend fun save(
        itemId: String,
        dimension: ScreenDimensionBucket,
        overrides: BookFormattingOverrides,
    ) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        if (overrides.isEmpty) {
            dao.deleteByItemId(sourceId, itemId, dimension.encode())
            return
        }
        dao.upsert(
            BookFormattingPreferencesEntity(
                sourceId = sourceId,
                itemId = itemId,
                screenDimensionBucket = dimension.encode(),
                fontSize = overrides.fontSize,
                theme = overrides.theme?.name,
                fontFamily = overrides.fontFamily?.encodePersistName(),
                lineSpacing = overrides.lineSpacing,
                margins = overrides.margins,
                orientation = overrides.orientation?.name,
                showChapterMap = overrides.showChapterMap,
                coloredChapterMap = overrides.coloredChapterMap,
                showReadingProgressLabels = overrides.showReadingProgressLabels,
                showCurrentChapterLabel = overrides.showCurrentChapterLabel,
                doublePageSpread = overrides.doublePageSpread,
                justifyText = overrides.justifyText,
                showReadingTimeEstimate = overrides.showReadingTimeEstimate,
            )
        )
    }

    override suspend fun clear(
        itemId: String,
        dimension: ScreenDimensionBucket,
    ) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        dao.deleteByItemId(sourceId, itemId, dimension.encode())
    }
}
