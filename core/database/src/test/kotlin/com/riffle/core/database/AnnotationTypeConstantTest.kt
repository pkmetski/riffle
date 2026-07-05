package com.riffle.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that [AnnotationEntity.TYPE_HIGHLIGHT] constant matches the SQL literal hardcoded in
 * [AnnotationDao.observeBooksWithHighlights].
 *
 * The @Query for observeBooksWithHighlights hardcodes `type = 'HIGHLIGHT'` because Room's @Query
 * cannot reference Kotlin constants inside the SQL string. This test runs at JVM load time to catch
 * renames of TYPE_HIGHLIGHT deterministically. If someone changes [AnnotationEntity.TYPE_HIGHLIGHT]
 * to a different value, this assertion will fail immediately and prevent the silent mismatch from
 * shipping.
 */
class AnnotationTypeConstantTest {
    @Test
    fun typeHighlightMatchesQueryLiteral() {
        assertEquals("HIGHLIGHT", AnnotationEntity.TYPE_HIGHLIGHT)
    }
}
