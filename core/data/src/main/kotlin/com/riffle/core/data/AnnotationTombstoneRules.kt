package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity

/**
 * Single source of truth for the ADR 0038 aged-tombstone predicate. Mirrors the
 * `AnnotationDao.purgeAgedTombstones` SQL clause exactly:
 *
 *   `deleted = 1 AND updatedAt < :cutoff AND updatedAt <= lastSyncedAt`
 *
 * Kept in Kotlin so the merge (`AnnotationMergeOrchestrator`) and push
 * (`AnnotationPushCoordinator`) paths can preview the sweep result in memory before touching
 * Room — the DELETE-before-purge ordering is what makes the sweep retriable when a WebDAV DELETE
 * throws. If this rule ever changes, both the SQL in [com.riffle.core.database.AnnotationDao]
 * and this predicate must move together.
 */
internal fun AnnotationEntity.isAgedSyncedTomb(cutoffMs: Long): Boolean =
    deleted && updatedAt < cutoffMs && updatedAt <= lastSyncedAt
