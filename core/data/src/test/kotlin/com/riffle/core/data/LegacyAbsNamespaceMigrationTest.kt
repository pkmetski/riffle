package com.riffle.core.data

import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.KomgaWebSourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the pre-`abs_` legacy-file classifier that both [WebDavAnnotationSyncTarget]
 * and [LocalDirectoryTarget] use to decide which annotation files to rename in place.
 *
 * Would-fail-if-reverted assertions:
 *  - A bare-UUID first segment classifies as legacy (`19621aae-…__book__annotations-x.jsonld`).
 *  - Already-prefixed segments (`abs_…`, `komga_…`) never re-migrate.
 *  - Non-UUID first segments (arbitrary user content) are left alone.
 *  - `migratedName` is idempotent — calling twice on the same input yields the same result.
 *  - Device-meta files (`<ns>__device-meta-<dev>.json`) migrate too, because the classifier
 *    only inspects the namespace segment, not the trailing filename shape.
 */
class LegacyAbsNamespaceMigrationTest {

    private val absUuid = "19621aae-1111-2222-3333-4a4a4a4a4a4a"

    @Test
    fun `bare UUID namespace segment classifies as legacy`() {
        assertTrue(LegacyAbsNamespaceMigration.isLegacyAbsNamespaceSegment(absUuid))
        assertTrue(
            LegacyAbsNamespaceMigration.isLegacyAbsFilename("${absUuid}__book1__annotations-devA.jsonld"),
        )
    }

    @Test
    fun `already-prefixed abs segments are not re-migrated`() {
        val prefixed = "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}$absUuid"
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsNamespaceSegment(prefixed))
        assertFalse(
            LegacyAbsNamespaceMigration.isLegacyAbsFilename("${prefixed}__book1__annotations-devA.jsonld"),
        )
    }

    @Test
    fun `komga-prefixed segments are not treated as legacy ABS`() {
        val komga = "${KomgaWebSourceDescriptor.KOMGA_NAMESPACE_PREFIX}$absUuid"
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsNamespaceSegment(komga))
        assertFalse(
            LegacyAbsNamespaceMigration.isLegacyAbsFilename("${komga}__book1__annotations-devA.jsonld"),
        )
    }

    @Test
    fun `non-UUID first segments are ignored`() {
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsNamespaceSegment("some-random-string"))
        assertFalse(
            LegacyAbsNamespaceMigration.isLegacyAbsFilename("plain__book1__annotations-devA.jsonld"),
        )
    }

    @Test
    fun `no double underscore separator means not a candidate`() {
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsFilename("just-a-single-file.txt"))
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsFilename(absUuid))
    }

    @Test
    fun `migratedName prepends abs prefix on legacy names`() {
        val legacy = "${absUuid}__book1__annotations-devA.jsonld"
        assertEquals(
            "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}$legacy",
            LegacyAbsNamespaceMigration.migratedName(legacy),
        )
    }

    @Test
    fun `migratedName is idempotent — second call is a no-op`() {
        val legacy = "${absUuid}__book1__annotations-devA.jsonld"
        val once = LegacyAbsNamespaceMigration.migratedName(legacy)
        val twice = LegacyAbsNamespaceMigration.migratedName(once)
        assertEquals(once, twice)
    }

    @Test
    fun `device-meta files also migrate — classifier looks at namespace only`() {
        val legacyMeta = "${absUuid}__device-meta-devA.json"
        assertTrue(LegacyAbsNamespaceMigration.isLegacyAbsFilename(legacyMeta))
        assertEquals(
            "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}$legacyMeta",
            LegacyAbsNamespaceMigration.migratedName(legacyMeta),
        )
    }

    @Test
    fun `unrelated content on the share is left alone`() {
        // Users may already have Nextcloud/Synology apple-double shadows, README files, etc.
        // The classifier must not touch anything that doesn't match the ABS legacy pattern.
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsFilename("._19621aae-1111-2222-3333-4a4a4a4a4a4a__book__annotations-dev.jsonld"))
        assertFalse(LegacyAbsNamespaceMigration.isLegacyAbsFilename("README.md"))
    }
}
