package com.riffle.core.domain

/**
 * Pure merge logic for annotations using last-write-wins (LWW) by (uuid, updatedAt).
 *
 * Merges parsed W3C annotations, applying deterministic last-write-wins semantics:
 * for each UUID, keeps the version with the highest updatedAt. Tombstones (deleted=true)
 * participate in LWW and are included in the result if they represent the latest version
 * of a UUID.
 *
 * No external state — pure function; idempotent and reproducible.
 */
class AnnotationMergeService {
    /**
     * Merge parsed annotations with existing parsed annotations.
     *
     * @param parsed List of parsed W3C annotations from sync source (e.g., file or server).
     * @param existing Existing W3CAnnotation records (e.g., from previous merge or Room conversion);
     *        if omitted, merge is just the parsed set as-is.
     * @param nowMs Wall-clock (ms) used to filter stale orphan incoming records (ADR 0038 rule 3).
     *        Omit or pass 0 to disable the filter (used by pure-merge tests).
     * @param staleOrphanCutoffMs Records in [parsed] whose `updatedAt < nowMs - staleOrphanCutoffMs`
     *        AND whose UUID has no matching row in [existing] are ignored — they are the delayed
     *        push from a peer that missed a household-wide sweep. Applying them would silently
     *        resurrect long-deleted content (see ADR 0038). Rows we already have locally, and
     *        fresh rows, are unaffected.
     * @return List of W3CAnnotation winners, ready to convert to AnnotationEntity.
     *
     * Algorithm:
     * 1. Drop stale orphans from [parsed] per the TTL rule above.
     * 2. Group all remaining annotations (parsed + existing) by UUID (annotation identity).
     * 3. For each UUID:
     *    - Pick the version with the highest updatedAt.
     *    - Tie-breaker: if updatedAt is equal, use lastModifiedByDeviceId lexicographically.
     * 4. Include tombstones (deleted=true) if they are the latest version.
     *
     * Deterministic: calling merge multiple times with the same input produces the same output.
     * Idempotent: merge(merge(parsed)) == merge(parsed) (given the same nowMs).
     */
    fun merge(
        parsed: List<W3CAnnotation>,
        existing: List<W3CAnnotation> = emptyList(),
        nowMs: Long = 0L,
        staleOrphanCutoffMs: Long = Long.MAX_VALUE,
    ): List<W3CAnnotation> {
        // Rule 3 — ignore stale orphans. A staleness threshold of Long.MAX_VALUE (or nowMs=0)
        // makes `staleCutoff` non-positive-or-past-Long.MIN and the predicate `updatedAt < cutoff`
        // never fires; use that as the "disabled" mode for callers that don't want the filter.
        val existingIds = existing.map { it.id }.toSet()
        val staleCutoff = if (staleOrphanCutoffMs == Long.MAX_VALUE) Long.MIN_VALUE
        else nowMs - staleOrphanCutoffMs
        val filteredParsed = parsed.filter { p ->
            !(p.updatedAt < staleCutoff && p.id !in existingIds)
        }

        // Combine parsed and existing.
        val allAnnotations = filteredParsed + existing

        // Group by UUID and pick the LWW winner for each group.
        val mergedByUuid = allAnnotations
            .groupBy { it.id }
            .mapValues { (uuid, group) ->
                // LWW: highest updatedAt; tie-break by lastModifiedByDeviceId lexicographically.
                group.maxWithOrNull(
                    compareBy<W3CAnnotation> { it.updatedAt }
                        .thenBy { it.lastModifiedByDeviceId }
                ) ?: error("Empty group for UUID $uuid")
            }

        // Return winners in stable order for reproducibility.
        return mergedByUuid.values
            .sortedBy { it.id }
    }
}
