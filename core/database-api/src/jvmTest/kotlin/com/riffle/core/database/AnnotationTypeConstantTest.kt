package com.riffle.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that [AnnotationEntity.TYPE_HIGHLIGHT] and [AnnotationEntity.TYPE_IMAGE] constants match
 * the SQL literals hardcoded in [AnnotationDao.observeBooksWithHighlights].
 *
 * The @Query for observeBooksWithHighlights hardcodes `type IN ('HIGHLIGHT', 'IMAGE')` because
 * Room's @Query cannot reference Kotlin constants inside the SQL string. This test runs at JVM
 * load time to catch renames deterministically. If someone changes either constant to a different
 * value, the corresponding assertion will fail immediately and prevent the silent mismatch from
 * shipping.
 */
class AnnotationTypeConstantTest {
    @Test
    fun typeHighlightMatchesQueryLiteral() {
        assertEquals("HIGHLIGHT", AnnotationEntity.TYPE_HIGHLIGHT)
    }

    @Test
    fun typeImageMatchesQueryLiteral() {
        assertEquals("IMAGE", AnnotationEntity.TYPE_IMAGE)
    }
}
