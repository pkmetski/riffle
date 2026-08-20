package com.riffle.core.data

import com.riffle.core.database.BookComicFormattingPreferencesDao
import com.riffle.core.database.BookComicFormattingPreferencesEntity
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.BookComicFormattingOverrides
import com.riffle.core.domain.comic.BookComicFormattingPreferencesStore
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.asComicBackgroundTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class BookComicFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookComicFormattingPreferencesDao,
) : BookComicFormattingPreferencesStore {

    // bookId is the canonical "<sourceId>::<itemId>" composite (ADR 0031).
    override fun overrides(bookId: String): Flow<BookComicFormattingOverrides> = flow {
        val (sourceId, itemId) = bookId.split("::", limit = 2)
        val entity = dao.getByItemId(sourceId, itemId)
        emit(entity?.toDomain() ?: BookComicFormattingOverrides())
    }

    override suspend fun save(bookId: String, overrides: BookComicFormattingOverrides) {
        if (overrides.isEmpty()) { reset(bookId); return }
        val (sourceId, itemId) = bookId.split("::", limit = 2)
        dao.upsert(
            BookComicFormattingPreferencesEntity(
                sourceId = sourceId,
                itemId = itemId,
                backgroundTheme = overrides.backgroundTheme?.asComicBackgroundTheme()?.name,
                panelViewOn = overrides.panelViewOn,
                panelOverflow = overrides.panelOverflow?.name,
                panelAnimationSpeedMs = overrides.panelAnimationSpeedMs,
            )
        )
    }

    override suspend fun reset(bookId: String) {
        val (sourceId, itemId) = bookId.split("::", limit = 2)
        dao.deleteByItemId(sourceId, itemId)
    }

    private fun BookComicFormattingPreferencesEntity.toDomain() = BookComicFormattingOverrides(
        backgroundTheme = backgroundTheme?.let {
            runCatching { ReaderTheme.valueOf(it) }.getOrNull()?.asComicBackgroundTheme()
        },
        panelViewOn = panelViewOn,
        panelOverflow = panelOverflow?.let {
            runCatching { PanelOverflowBehavior.valueOf(it) }.getOrNull()
        },
        panelAnimationSpeedMs = panelAnimationSpeedMs,
    )
}
