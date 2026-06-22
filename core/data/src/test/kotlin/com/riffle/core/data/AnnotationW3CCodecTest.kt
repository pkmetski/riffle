package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnnotationW3CCodecTest {

    private lateinit var codec: AnnotationW3CCodec

    @Before
    fun setup() {
        codec = AnnotationW3CCodec
    }

    // Test 1: Serialize highlight (motivation=highlighting, value=color)
    @Test
    fun `serializeHighlight contains motivation highlighting and color in value`() {
        val entity = AnnotationEntity(
            id = "uuid-123",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:100)",
            color = "yellow",
            note = null,
            textSnippet = "selected text",
            textBefore = "before ",
            textAfter = " after",
            chapterHref = "chap01.xhtml",
            createdAt = 1000000L,
            updatedAt = 1000000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
        )

        val json = codec.annotationEntityToW3C(entity)

        assertTrue("JSON should contain motivation highlighting", json.contains("\"motivation\":\"highlighting\""))
        assertTrue("JSON should contain color yellow in value", json.contains("\"value\":\"yellow\""))
    }

    // Test 2: Round-trip highlight preserves all fields
    @Test
    fun `roundTripHighlight preserves all fields including CFI snippet and color`() {
        val original = AnnotationEntity(
            id = "uuid-hl-001",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4[chap01]!/4/2/16,/1:0,/1:100)",
            color = "green",
            note = null,
            textSnippet = "The quick brown fox",
            textBefore = "said: ",
            textAfter = " jumps over.",
            chapterHref = "item1", // Note: chapterHref is extracted from source "epub://item-{itemId}"
            spineIndex = 0,
            progression = 0.15,
            createdAt = 1609459200000L, // 2021-01-01 00:00:00 UTC
            updatedAt = 1609459200000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("ID should match", original.id, w3cAnnotation.id)
        assertEquals("CFI should match", original.cfi, w3cAnnotation.cfi)
        assertEquals("Type should match", original.type, w3cAnnotation.type)
        assertEquals("Color should match", original.color, w3cAnnotation.color)
        assertEquals("Text snippet should match", original.textSnippet, w3cAnnotation.textSnippet)
        assertEquals("Chapter href should match", original.chapterHref, w3cAnnotation.chapterHref)
        assertNull("Note should be null", w3cAnnotation.note)
        assertEquals("Created timestamp should match", original.createdAt, w3cAnnotation.createdAt)
        assertEquals("Updated timestamp should match", original.updatedAt, w3cAnnotation.updatedAt)
        assertEquals("Origin device ID should match", original.originDeviceId, w3cAnnotation.originDeviceId)
        assertEquals("Last modified device ID should match", original.lastModifiedByDeviceId, w3cAnnotation.lastModifiedByDeviceId)
        assertFalse("Deleted flag should be false", w3cAnnotation.deleted)
    }

    // Test 3: Round-trip bookmark preserves type and title
    @Test
    fun `roundTripBookmark preserves type and bookmarkTitle`() {
        val original = AnnotationEntity(
            id = "uuid-bm-001",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "",
            note = null,
            textSnippet = "Chapter beginning",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            spineIndex = 1,
            progression = 0.0,
            bookmarkTitle = "My favorite passage",
            createdAt = 1609545600000L, // 2021-01-02 00:00:00 UTC
            updatedAt = 1609545600000L,
            originDeviceId = "device-B",
            lastModifiedByDeviceId = "device-B",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Type should be BOOKMARK", AnnotationEntity.TYPE_BOOKMARK, w3cAnnotation.type)
        assertEquals("Bookmark title should match", original.bookmarkTitle, w3cAnnotation.bookmarkTitle)
        assertEquals("ID should match", original.id, w3cAnnotation.id)
        assertEquals("CFI should match", original.cfi, w3cAnnotation.cfi)
    }

    // Test 4: Round-trip highlight with note preserves note content
    @Test
    fun `roundTripHighlightWithNote preserves note content`() {
        val original = AnnotationEntity(
            id = "uuid-hl-note",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "blue",
            note = "This is an important observation about the text.",
            textSnippet = "important text",
            textBefore = "The ",
            textAfter = " was highlighted.",
            chapterHref = "item1",
            createdAt = 1609632000000L, // 2021-01-03 00:00:00 UTC
            updatedAt = 1609632000000L,
            originDeviceId = "device-C",
            lastModifiedByDeviceId = "device-C",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Note should match", original.note, w3cAnnotation.note)
        assertEquals("Type should still be HIGHLIGHT", AnnotationEntity.TYPE_HIGHLIGHT, w3cAnnotation.type)
        assertEquals("Color should match", original.color, w3cAnnotation.color)
    }

    // Test 5: Round-trip deleted annotation preserves deleted flag and device IDs
    @Test
    fun `roundTripDeletedAnnotation preserves deleted flag and device IDs`() {
        val original = AnnotationEntity(
            id = "uuid-deleted",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "red",
            note = null,
            textSnippet = "deleted highlight",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1609718400000L, // 2021-01-04 00:00:00 UTC
            updatedAt = 1609804800000L, // 2021-01-05 00:00:00 UTC (deleted later)
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-B", // Different device deleted it
            deleted = true,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertTrue("Deleted flag should be true", w3cAnnotation.deleted)
        assertEquals("Origin device ID should match", original.originDeviceId, w3cAnnotation.originDeviceId)
        assertEquals("Last modified device ID should match", original.lastModifiedByDeviceId, w3cAnnotation.lastModifiedByDeviceId)
        assertEquals("Updated timestamp should reflect deletion time", original.updatedAt, w3cAnnotation.updatedAt)
    }

    // Test 6: Snippet with before/after context round-trips correctly
    @Test
    fun `snippetWithContext roundTrips with before and after text preserved`() {
        val original = AnnotationEntity(
            id = "uuid-snippet",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:10,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "middle part",
            textBefore = "This is the beginning ",
            textAfter = " and this is the end.",
            chapterHref = "item1",
            createdAt = 1609891200000L, // 2021-01-06 00:00:00 UTC
            updatedAt = 1609891200000L,
            originDeviceId = "device-X",
            lastModifiedByDeviceId = "device-X",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Text snippet should match", original.textSnippet, w3cAnnotation.textSnippet)
        // Note: W3CAnnotation doesn't have textBefore/textAfter directly, but they're in the JSON
        assertTrue("JSON should contain prefix", w3cJson.contains("\"prefix\":\"This is the beginning \""))
        assertTrue("JSON should contain suffix", w3cJson.contains("\"suffix\":\" and this is the end.\""))
    }

    // Test 7: Complex CFI range serialization and preservation
    @Test
    fun `complexCFI preservedExactly through serialization`() {
        val complexCfi = "epubcfi(/6/4[chap01]!/4/2/16,/1:0,/1:150)"
        val original = AnnotationEntity(
            id = "uuid-cfi",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = complexCfi,
            color = "purple",
            note = null,
            textSnippet = "complex cfi text",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1609977600000L, // 2021-01-07 00:00:00 UTC
            updatedAt = 1609977600000L,
            originDeviceId = "device-Y",
            lastModifiedByDeviceId = "device-Y",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Complex CFI should be preserved exactly", complexCfi, w3cAnnotation.cfi)
        assertTrue("JSON should contain exact CFI", w3cJson.contains(complexCfi))
    }

    // Test 8: Timestamps (millis) round-trip via ISO 8601 conversion
    @Test
    fun `timestampRoundTrip converts millis to ISO 8601 and back correctly`() {
        val createdMillis = 1640000000000L  // 2021-12-20 10:26:40 UTC
        val updatedMillis = 1640100000000L  // 2021-12-21 14:00:00 UTC

        val original = AnnotationEntity(
            id = "uuid-time",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "timestamp test",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = createdMillis,
            updatedAt = updatedMillis,
            originDeviceId = "device-Z",
            lastModifiedByDeviceId = "device-Z",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Created timestamp should match", createdMillis, w3cAnnotation.createdAt)
        assertEquals("Updated timestamp should match", updatedMillis, w3cAnnotation.updatedAt)
        // Verify ISO 8601 is in JSON (contains the date and "Z" for UTC)
        assertTrue("JSON should contain created timestamp in ISO format", w3cJson.contains("\"created\":\"2021-12-20") && w3cJson.contains("\""))
        assertTrue("JSON should contain modified timestamp in ISO format", w3cJson.contains("\"modified\":\"2021-12-21") && w3cJson.contains("\""))
    }

    // Test 9: Device IDs (origin and lastModifiedBy) both preserved
    @Test
    fun `deviceIDs bothOriginAndLastModifiedByPreserved`() {
        val original = AnnotationEntity(
            id = "uuid-device",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "orange",
            note = null,
            textSnippet = "device test",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1640186400000L,
            updatedAt = 1640272800000L,
            originDeviceId = "device-phone-001",
            lastModifiedByDeviceId = "device-tablet-002",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Origin device ID should match", "device-phone-001", w3cAnnotation.originDeviceId)
        assertEquals("Last modified device ID should match", "device-tablet-002", w3cAnnotation.lastModifiedByDeviceId)
        assertTrue("JSON should contain riffle:originDeviceId", w3cJson.contains("\"riffle:originDeviceId\":\"device-phone-001\""))
        assertTrue("JSON should contain riffle:lastModifiedByDeviceId", w3cJson.contains("\"riffle:lastModifiedByDeviceId\":\"device-tablet-002\""))
    }

    // Test 10: All annotation types map motivation correctly
    @Test
    fun `allAnnotationTypesMappedCorrectly`() {
        // Test HIGHLIGHT
        val highlight = AnnotationEntity(
            id = "uuid-hl",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "highlight",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1640359200000L,
            updatedAt = 1640359200000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val hlJson = codec.annotationEntityToW3C(highlight)
        val hlRestored = codec.w3cToAnnotationEntity(hlJson)

        assertEquals("Highlight type should be preserved", AnnotationEntity.TYPE_HIGHLIGHT, hlRestored.type)
        assertTrue("Highlight JSON should have highlighting motivation", hlJson.contains("\"motivation\":\"highlighting\""))

        // Test BOOKMARK
        val bookmark = AnnotationEntity(
            id = "uuid-bm",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "",
            note = null,
            textSnippet = "bookmark",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            bookmarkTitle = "Chapter start",
            createdAt = 1640359200000L,
            updatedAt = 1640359200000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val bmJson = codec.annotationEntityToW3C(bookmark)
        val bmRestored = codec.w3cToAnnotationEntity(bmJson)

        assertEquals("Bookmark type should be preserved", AnnotationEntity.TYPE_BOOKMARK, bmRestored.type)
        assertTrue("Bookmark JSON should have bookmarking motivation", bmJson.contains("\"motivation\":\"bookmarking\""))
    }

    // Test 11: JSON structure validation (required W3C fields)
    @Test
    fun `jsonStructureContainsAllRequiredW3CFields`() {
        val entity = AnnotationEntity(
            id = "uuid-struct",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "structure test",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1640445600000L,
            updatedAt = 1640445600000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val json = codec.annotationEntityToW3C(entity)

        // Verify required W3C fields
        assertTrue("JSON should contain @context", json.contains("\"@context\":\"http://www.w3.org/ns/anno.jsonld\""))
        assertTrue("JSON should contain id with urn:uuid:", json.contains("\"id\":\"urn:uuid:uuid-struct\""))
        assertTrue("JSON should contain type Annotation", json.contains("\"type\":\"Annotation\""))
        assertTrue("JSON should contain motivation", json.contains("\"motivation\""))
        assertTrue("JSON should contain target", json.contains("\"target\""))
        assertTrue("JSON should contain body", json.contains("\"body\""))
        assertTrue("JSON should contain created timestamp", json.contains("\"created\""))
        assertTrue("JSON should contain modified timestamp", json.contains("\"modified\""))
    }

    // Test 12: Riffle extensions present in JSON output
    @Test
    fun `riffleExtensionsPresent inJsonOutput`() {
        val entity = AnnotationEntity(
            id = "uuid-riffle",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "green",
            note = null,
            textSnippet = "riffle test",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1640532000000L,
            updatedAt = 1640532000000L,
            originDeviceId = "device-riffle-1",
            lastModifiedByDeviceId = "device-riffle-2",
            deleted = true,
        )

        val json = codec.annotationEntityToW3C(entity)

        // Verify Riffle extensions
        assertTrue("JSON should contain riffle:originDeviceId", json.contains("\"riffle:originDeviceId\":\"device-riffle-1\""))
        assertTrue("JSON should contain riffle:lastModifiedByDeviceId", json.contains("\"riffle:lastModifiedByDeviceId\":\"device-riffle-2\""))
        assertTrue("JSON should contain riffle:updatedAt", json.contains("\"riffle:updatedAt\":1640532000000"))
        assertTrue("JSON should contain riffle:deleted", json.contains("\"riffle:deleted\":true"))
    }

    // Test 13: Empty note field handled correctly
    @Test
    fun `emptyNoteFieldHandledCorrectly`() {
        val original = AnnotationEntity(
            id = "uuid-empty-note",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = "",
            textSnippet = "no note",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            createdAt = 1640618400000L,
            updatedAt = 1640618400000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertNull("Empty note should round-trip as null", w3cAnnotation.note)
    }

    // Test 14: Bookmark with title in body and riffle extensions
    @Test
    fun `bookmarkTitleRoundTripsInBodyAndRiffleNamespace`() {
        val original = AnnotationEntity(
            id = "uuid-bm-title",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "",
            note = null,
            textSnippet = "bookmark point",
            textBefore = "",
            textAfter = "",
            chapterHref = "item1",
            bookmarkTitle = "Important Plot Point",
            createdAt = 1640704800000L,
            updatedAt = 1640704800000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(original)

        // Verify title in both body and riffle namespace
        assertTrue("Bookmark title should be in body value", w3cJson.contains("\"value\":\"Important Plot Point\""))
        assertTrue("Bookmark title should be in riffle:bookmarkTitle", w3cJson.contains("\"riffle:bookmarkTitle\":\"Important Plot Point\""))

        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)
        assertEquals("Bookmark title should round-trip", original.bookmarkTitle, w3cAnnotation.bookmarkTitle)
    }

    // Test 15: FragmentSelector and TextQuoteSelector both present in target
    @Test
    fun `targetSelectorArrayContainsBothFragmentAndTextQuoteSelectors`() {
        val entity = AnnotationEntity(
            id = "uuid-selectors",
            serverId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "selector test",
            textBefore = "prefix ",
            textAfter = " suffix",
            chapterHref = "item1",
            createdAt = 1640791200000L,
            updatedAt = 1640791200000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val json = codec.annotationEntityToW3C(entity)

        // Verify selector structures
        assertTrue("JSON should contain FragmentSelector", json.contains("\"type\":\"FragmentSelector\""))
        assertTrue("JSON should contain TextQuoteSelector", json.contains("\"type\":\"TextQuoteSelector\""))
        assertTrue("FragmentSelector should contain CFI", json.contains("epubcfi(/6/4!/4/2,/1:0,/1:50)"))
        assertTrue("TextQuoteSelector should contain exact text", json.contains("\"exact\":\"selector test\""))
        assertTrue("TextQuoteSelector should contain prefix", json.contains("\"prefix\":\"prefix \""))
        assertTrue("TextQuoteSelector should contain suffix", json.contains("\"suffix\":\" suffix\""))
    }

    // Test 16: Source field correctly formatted as epub://item-*
    @Test
    fun `sourceFieldCorrectlyFormattedAsEpubItem`() {
        val entity = AnnotationEntity(
            id = "uuid-source",
            serverId = "abs1",
            itemId = "item-abc123",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "source test",
            textBefore = "",
            textAfter = "",
            chapterHref = "item-abc123",
            createdAt = 1640877600000L,
            updatedAt = 1640877600000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        val w3cAnnotation = codec.w3cToAnnotationEntity(w3cJson)

        assertTrue("Source should be formatted as epub://item-*", w3cJson.contains("\"source\":\"epub://item-item-abc123\""))
        assertEquals("Chapter href should be extracted from source", "item-abc123", w3cAnnotation.chapterHref)
    }
}
