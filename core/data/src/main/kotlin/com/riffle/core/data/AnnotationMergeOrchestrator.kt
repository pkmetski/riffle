package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationMergeService
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.EmbeddedFigure
import com.riffle.core.domain.W3CAnnotation

/**
 * Owns the read-list → merge → upsert path executed on book-open and reused by the live-sync
 * poll loop when peer files exist. Extracted from [AnnotationSyncController] so the merge policy
 * (ADR 0038 tombstone sweep + stale-orphan guard) can be exercised in isolation.
 */
internal class AnnotationMergeOrchestrator(
    private val mergeService: AnnotationMergeService,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val statusStore: AnnotationSyncStatusStore,
    private val sentinelWriter: DeviceMetaSentinelWriter,
    private val clock: () -> Long,
    private val tombstoneTtlMs: Long,
) {
    /** Full sync on book open: list peer files, read each, merge, upsert. */
    suspend fun syncOnOpen(
        target: AnnotationSyncTarget,
        sourceId: String,
        namespace: String,
        itemId: String,
    ) {
        val filenames = try {
            target.list(namespace, itemId)
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
            // Don't enqueue a sweep — sync-on-open failures are retried the next book open.
            return
        }
        mergeFromListing(target, sourceId, namespace, itemId, filenames)
    }

    /**
     * Merge the device files already listed in [filenames] into Room using the same [target]
     * instance that produced the listing. Reports a [CycleOutcome] to the status store on
     * success or failure.
     */
    suspend fun mergeFromListing(
        target: AnnotationSyncTarget,
        sourceId: String,
        namespace: String,
        itemId: String,
        filenames: List<String>,
    ) {
        try {
            val parsedAnnotations = mutableListOf<W3CAnnotation>()
            for (filename in filenames) {
                try {
                    val jsonString = target.read(namespace, itemId, filename) ?: continue
                    parsedAnnotations += AnnotationW3CCodec.w3cFileToAnnotations(jsonString)
                } catch (_: Exception) {
                    // Skip corrupt files silently
                }
            }

            // ADR 0038 rule 1+2 — sweep aged tombstones and DELETE the resulting empty file.
            // Ordering matters: compute the hypothetical post-sweep state in memory FIRST, so we
            // can attempt the DELETE before mutating Room. If the DELETE throws, Room is untouched
            // and the next sync sees the same transition and retries. Sweeping first would leave
            // Room empty with a stale file on WebDAV and no signal to retry the DELETE.
            val now = clock()
            val cutoff = now - tombstoneTtlMs
            val beforeSweep = annotationDao.getAllForItemIncludingDeleted(sourceId, itemId)
            val nonPurgeable = beforeSweep.filter { !it.isAgedSyncedTomb(cutoff) }
            val localById = nonPurgeable.associateBy { it.id }
            val localExisting = nonPurgeable.map { AnnotationW3CCodec.entityToW3CAnnotation(it) }

            // ADR 0038 rule 3 — ignore stale orphans. Incoming rows we've never seen locally and
            // whose updatedAt is past TTL are the delayed push from a peer that missed a
            // household-wide sweep; applying them would silently resurrect long-deleted content.
            val merged = mergeService.merge(
                parsed = parsedAnnotations,
                existing = localExisting,
                nowMs = now,
                staleOrphanCutoffMs = tombstoneTtlMs,
            )

            // Rule 2 DELETE branch — the sweep would empty us and this device's own file is on
            // WebDAV. DELETE first, then commit the sweep and any merge results.
            if (merged.isEmpty() && beforeSweep.isNotEmpty()) {
                val ownFilename = AnnotationFilenames.forDevice(deviceIdStore.getOrCreate())
                if (ownFilename in filenames) {
                    target.delete(namespace, itemId, ownFilename)
                }
            }

            // Safe to commit Room mutations now — either no DELETE was needed, or it succeeded.
            annotationDao.purgeAgedTombstones(sourceId, itemId, cutoff)

            val entities = merged.map { w3cAnnotation ->
                val existing = localById[w3cAnnotation.id]
                val preservedLastSyncedAt = when {
                    existing == null -> 0L
                    existing.updatedAt >= w3cAnnotation.updatedAt -> existing.lastSyncedAt
                    else -> 0L
                }
                AnnotationEntity(
                    id = w3cAnnotation.id,
                    sourceId = sourceId,
                    itemId = itemId,
                    type = w3cAnnotation.type,
                    cfi = w3cAnnotation.cfi,
                    color = w3cAnnotation.color ?: AnnotationEntity.COLOR_YELLOW,
                    note = w3cAnnotation.note,
                    textSnippet = w3cAnnotation.textSnippet,
                    textBefore = w3cAnnotation.textBefore,
                    textAfter = w3cAnnotation.textAfter,
                    // chapterHref round-trip: the W3C wire format encodes `target.source` as
                    // `epub://item-<itemId>` (see AnnotationW3CCodec), so a parsed W3CAnnotation
                    // carries the itemId in `chapterHref`, not the real chapter path. LWW ties
                    // (same device, same updatedAt right after our own push) resolve to the
                    // parsed row, so upserting `w3cAnnotation.chapterHref` unconditionally
                    // silently overwrites the local row's real href with the itemId — and the
                    // reader's page-bookmarked indicator, which matches on normalized chapterHref,
                    // goes dark on re-open. The CFI is immutable per annotation, so the local
                    // href is authoritative when we have a row.
                    chapterHref = existing?.chapterHref ?: w3cAnnotation.chapterHref,
                    // Sort-key round-trip: prefer the locally-stored values when we already have a
                    // row (the CFI hasn't moved, so the sort key computed at creation time is still
                    // correct). Otherwise trust the peer's riffle:spineIndex / riffle:progression
                    // extension so cross-device bookmarks land in reading order on first receive.
                    // Files predating the extension deserialize as 0/0.0 — those brand-new peer
                    // rows will cluster at the top until re-opened locally, which is the same
                    // pre-extension behavior.
                    spineIndex = existing?.spineIndex ?: w3cAnnotation.spineIndex,
                    progression = existing?.progression ?: w3cAnnotation.progression,
                    bookmarkTitle = w3cAnnotation.bookmarkTitle ?: "",
                    createdAt = w3cAnnotation.createdAt,
                    updatedAt = w3cAnnotation.updatedAt,
                    originDeviceId = w3cAnnotation.originDeviceId,
                    lastModifiedByDeviceId = w3cAnnotation.lastModifiedByDeviceId,
                    deleted = w3cAnnotation.deleted,
                    lastSyncedAt = preservedLastSyncedAt,
                    // Preserve local imageBytes / per-figure imageBytes / per-figure charOffset
                    // when the remote row was written by an older peer that didn't sync them.
                    // Without this, a WebDAV round-trip resets a locally-rasterized figure to
                    // "[figure image not captured]" and drops charOffset (which controls the
                    // caption position in the elided view). Explicit override still wins when
                    // the remote row does carry the extension fields.
                    embeddedFigures = embeddedFiguresListToColumn(
                        preserveLocalFigureExtensions(
                            remote = w3cAnnotation.embeddedFigures,
                            local = existing?.embeddedFigures?.let { embeddedFiguresColumnToList(it) },
                        ),
                    ),
                    imageHref = w3cAnnotation.imageHref,
                    imageSvg = w3cAnnotation.imageSvg,
                    imageBytes = w3cAnnotation.imageBytes ?: existing?.imageBytes,
                    // ADR 0046: emphasis styles round-trip via a riffle:emphasis body. A peer that
                    // didn't emit the styles token leaves the column null; that combined with
                    // type=TYPE_EMPHASIS is an inert row the renderer ignores.
                    emphasisStyles = w3cAnnotation.emphasisStyles ?: existing?.emphasisStyles,
                )
            }

            for (entity in entities) {
                annotationDao.upsert(entity)
            }
            statusStore.report(CycleOutcome.Success(clock()))
            sentinelWriter.writeQuietly(target, namespace, sourceId)
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
        }
    }

    /**
     * Merge each remote [EmbeddedFigure] with its matching local counterpart (by href filename
     * or SVG-prefix key), promoting local's `imageBytes` and `charOffset` when the remote row's
     * values are null. Older peers don't sync these extension fields ([AnnotationW3CCodec] added
     * them 2026-07-14); without this promotion a round-trip resets a locally-rasterized figure
     * and drops the figure's position-in-range, which flips the caption to render ABOVE the
     * image in the elided view (bug reproduced on AVD 5554).
     */
    private fun preserveLocalFigureExtensions(
        remote: List<EmbeddedFigure>?,
        local: List<EmbeddedFigure>?,
    ): List<EmbeddedFigure>? {
        if (remote.isNullOrEmpty()) return remote
        if (local.isNullOrEmpty()) return remote
        val localByKey = local.associateBy { fig -> figureMatchKey(fig) }
        return remote.map { r ->
            val key = figureMatchKey(r)
            val l = localByKey[key]
            if (l == null) r
            else r.copy(
                imageBytes = r.imageBytes ?: l.imageBytes,
                charOffset = r.charOffset ?: l.charOffset,
            )
        }
    }

    private fun figureMatchKey(fig: EmbeddedFigure): String {
        val href = fig.href
        if (href != null) {
            val trimmed = href.substringBefore('?').substringBefore('#')
            val slash = trimmed.lastIndexOf('/')
            return "h:" + if (slash >= 0) trimmed.substring(slash + 1) else trimmed
        }
        val svg = fig.svg
        if (svg != null) return "s:" + svg.take(200)
        return "?:${fig.order}"
    }
}
