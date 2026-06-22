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
     * @return List of W3CAnnotation winners, ready to convert to AnnotationEntity.
     *
     * Algorithm:
     * 1. Group all annotations (parsed + existing) by UUID (annotation identity).
     * 2. For each UUID:
     *    - Pick the version with the highest updatedAt.
     *    - Tie-breaker: if updatedAt is equal, use lastModifiedByDeviceId lexicographically.
     * 3. Include tombstones (deleted=true) if they are the latest version.
     *
     * Deterministic: calling merge multiple times with the same input produces the same output.
     * Idempotent: merge(merge(parsed)) == merge(parsed).
     */
    fun merge(
        parsed: List<W3CAnnotation>,
        existing: List<W3CAnnotation> = emptyList(),
    ): List<W3CAnnotation> {
        // Combine parsed and existing.
        val allAnnotations = parsed + existing

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
