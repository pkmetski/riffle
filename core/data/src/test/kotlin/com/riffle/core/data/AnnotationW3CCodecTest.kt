package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.EmbeddedFigure
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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
            sourceId = "abs1",
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

    // Test 17: TextQuoteSelector prefix/suffix round-trip through parse so the disambiguation
    // context survives a remote → local sync. The codec already SERIALIZES textBefore/textAfter
    // (see test 15), but until W3CAnnotation carried these fields the parsed values were dropped
    // on the way back; without this any annotation synced from another device anchored to the
    // FIRST occurrence of its snippet in the chapter (bug fixed alongside the continuous-mode
    // --- w3cFileToAnnotations: parses per-device files (JSON array layout) ---

    @Test
    fun `w3cFileToAnnotations parses a two-element array`() {
        val a = codec.annotationEntityToW3C(highlight("uuid-a"))
        val b = codec.annotationEntityToW3C(highlight("uuid-b"))
        val file = "[\n$a,\n$b\n]"

        val parsed = codec.w3cFileToAnnotations(file)

        assertEquals(listOf("uuid-a", "uuid-b"), parsed.map { it.id })
    }

    @Test
    fun `w3cFileToAnnotations parses an empty array`() {
        assertEquals(emptyList<Any>(), codec.w3cFileToAnnotations("[]"))
    }

    @Test
    fun `w3cFileToAnnotations accepts a bare JSON object for backward-compat`() {
        val single = codec.annotationEntityToW3C(highlight("uuid-x"))

        val parsed = codec.w3cFileToAnnotations(single)

        assertEquals(listOf("uuid-x"), parsed.map { it.id })
    }

    @Test
    fun `w3cFileToAnnotations drops entries whose id is empty after parse`() {
        // Object missing the `id` field — w3cObjectToAnnotation will return id="" and we filter it.
        val malformed = """[{"type":"Annotation"}, ${codec.annotationEntityToW3C(highlight("uuid-keep"))}]"""

        val parsed = codec.w3cFileToAnnotations(malformed)

        assertEquals(listOf("uuid-keep"), parsed.map { it.id })
    }

    @Test
    fun `w3cFileToAnnotations returns empty on malformed JSON instead of throwing`() {
        assertEquals(emptyList<Any>(), codec.w3cFileToAnnotations("this is { not json"))
    }

    @Test
    fun `w3cFileToAnnotations preserves both highlight and bookmark motivations within one file`() {
        val h = codec.annotationEntityToW3C(highlight("uuid-h"))
        val b = codec.annotationEntityToW3C(bookmark("uuid-b"))
        val file = "[\n$h,\n$b\n]"

        val parsed = codec.w3cFileToAnnotations(file)

        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, parsed.first { it.id == "uuid-h" }.type)
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, parsed.first { it.id == "uuid-b" }.type)
    }

    private fun highlight(id: String) = AnnotationEntity(
        id = id, sourceId = "abs1", itemId = "item-1", type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)", color = "yellow", note = null,
        textSnippet = "x", textBefore = "", textAfter = "", chapterHref = "c1",
        createdAt = 1000L, updatedAt = 1000L, originDeviceId = "dev", lastModifiedByDeviceId = "dev",
    )

    private fun bookmark(id: String) = AnnotationEntity(
        id = id, sourceId = "abs1", itemId = "item-1", type = AnnotationEntity.TYPE_BOOKMARK,
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:0)", color = "", note = null,
        textSnippet = "", textBefore = "", textAfter = "", chapterHref = "c1",
        bookmarkTitle = "ch1 · 12%",
        createdAt = 1000L, updatedAt = 1000L, originDeviceId = "dev", lastModifiedByDeviceId = "dev",
    )

    // wrong-occurrence highlight fix).
    @Test
    fun `textQuoteSelectorPrefixAndSuffixRoundTripThroughParse`() {
        val entity = AnnotationEntity(
            id = "uuid-roundtrip",
            sourceId = "abs1",
            itemId = "item-1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:50)",
            color = "yellow",
            note = null,
            textSnippet = "Куджиа",
            textBefore = "Али ",
            textAfter = " решил да замине",
            chapterHref = "item-1",
            createdAt = 1640877600000L,
            updatedAt = 1640877600000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            deleted = false,
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        val parsed = codec.w3cToAnnotationEntity(w3cJson)

        assertEquals("Али ", parsed.textBefore)
        assertEquals(" решил да замине", parsed.textAfter)
        assertEquals("Куджиа", parsed.textSnippet)
    }

    // ---- PDF locator round-trip (PDF parity v1) ----------------------------
    //
    // PDF annotation rows store a Readium Locator JSON object in the `cfi`
    // column (a misnomer kept for schema stability — see CONTEXT.md and the
    // PDF parity design spec). The codec must:
    //
    // 1. emit a `pdf://item-<id>` source URL,
    // 2. emit a FragmentSelector with `conformsTo = RFC 3778` and `value =
    //    page=N` from the locator's `locations.position`,
    // 3. nest a RefinedBy DataPositionSelector carrying `start/end` char
    //    indices from `locations.otherLocations.{charStart,charEnd}`,
    // 4. attach persisted highlight quads as a Riffle-namespaced property,
    // 5. round-trip back to the same Locator JSON string on decode (Locator
    //    field order isn't significant, so tests verify by re-parsing JSON,
    //    not by string equality).

    private val pdfHighlightCfi = """
        {"href":"books/x.pdf","type":"application/pdf","locations":
        {"position":42,"otherLocations":
        {"charStart":1503,"charEnd":1547,
         "quads":[{"x":120,"y":280,"w":340,"h":18},
                  {"x":68,"y":300,"w":280,"h":18}]}}}
    """.trimIndent().replace("\n", "").replace(" ", "")

    private val pdfBookmarkCfi = """
        {"href":"books/x.pdf","type":"application/pdf","locations":{"position":7}}
    """.trimIndent()

    private fun pdfHighlightEntity() = AnnotationEntity(
        id = "uuid-pdf-1",
        sourceId = "abs1",
        itemId = "pdf-item",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = pdfHighlightCfi,
        color = "yellow",
        note = null,
        textSnippet = "the selected passage",
        textBefore = "before context for disambiguation ",
        textAfter = " after context for disambiguation",
        chapterHref = "books/x.pdf",
        createdAt = 1700000000000L,
        updatedAt = 1700000000000L,
        originDeviceId = "device-A",
        lastModifiedByDeviceId = "device-A",
    )

    @Test
    fun `PDF highlight serializes with RFC 3778 FragmentSelector and refinedBy DataPositionSelector`() {
        val json = codec.annotationEntityToW3C(pdfHighlightEntity())
        assertTrue("source uses pdf:// scheme", json.contains("\"pdf://item-pdf-item\""))
        assertTrue("FragmentSelector conformsTo RFC 3778",
            json.contains("\"http://tools.ietf.org/rfc/rfc3778\""))
        assertTrue("value carries page= fragment", json.contains("\"value\":\"page=42\""))
        assertTrue("RefinedBy is a DataPositionSelector",
            json.contains("\"DataPositionSelector\""))
        assertTrue("char range start present", json.contains("\"start\":1503"))
        assertTrue("char range end present", json.contains("\"end\":1547"))
        assertTrue("quads ride as Riffle-namespaced property",
            json.contains("\"riffle:quads\""))
    }

    @Test
    fun `PDF highlight round-trips back to equivalent Locator JSON`() {
        val w3cJson = codec.annotationEntityToW3C(pdfHighlightEntity())
        val parsed = codec.w3cToAnnotationEntity(w3cJson)

        val rawJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val roundTripped = rawJson.parseToJsonElement(parsed.cfi).jsonObject

        assertEquals("application/pdf", roundTripped["type"]?.jsonPrimitive?.content)
        // The `riffle:chapterHref` extension carries the real chapter path across the wire, so
        // both the parsed W3CAnnotation and the reassembled locator's `href` recover the
        // entity's original chapterHref instead of the source-derived itemId. Files predating
        // the extension fall back to the itemId (same PDF ⇄ EPUB shape as before).
        assertEquals("books/x.pdf", roundTripped["href"]?.jsonPrimitive?.content)
        assertEquals("books/x.pdf", parsed.chapterHref)

        val locations = roundTripped["locations"]?.jsonObject!!
        assertEquals(42, locations["position"]?.jsonPrimitive?.intOrNull)
        val other = locations["otherLocations"]?.jsonObject!!
        assertEquals(1503, other["charStart"]?.jsonPrimitive?.intOrNull)
        assertEquals(1547, other["charEnd"]?.jsonPrimitive?.intOrNull)
        val quads = other["quads"]?.jsonArray!!
        assertEquals(2, quads.size)
    }

    @Test
    fun `PDF bookmark (no char range) serializes without RefinedBy`() {
        val entity = pdfHighlightEntity().copy(
            id = "uuid-pdf-bm-1",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = pdfBookmarkCfi,
            color = "",
            textSnippet = "",
            textBefore = "",
            textAfter = "",
            bookmarkTitle = "Page 7",
        )
        val json = codec.annotationEntityToW3C(entity)
        assertTrue("bookmark motivation", json.contains("\"motivation\":\"bookmarking\""))
        assertTrue("source uses pdf:// scheme", json.contains("\"pdf://item-pdf-item\""))
        assertTrue("value carries page= fragment", json.contains("\"value\":\"page=7\""))
        assertFalse("no RefinedBy for char-rangeless bookmark",
            json.contains("\"DataPositionSelector\""))
        assertFalse("no quads on a bookmark", json.contains("\"riffle:quads\""))
    }

    @Test
    fun `PDF bookmark round-trips with position only`() {
        val entity = pdfHighlightEntity().copy(
            id = "uuid-pdf-bm-2",
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = pdfBookmarkCfi,
            color = "",
            textSnippet = "",
            textBefore = "",
            textAfter = "",
            bookmarkTitle = "Page 7",
        )
        val w3cJson = codec.annotationEntityToW3C(entity)
        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        val rawJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val roundTripped = rawJson.parseToJsonElement(parsed.cfi).jsonObject
        assertEquals("application/pdf", roundTripped["type"]?.jsonPrimitive?.content)
        val locations = roundTripped["locations"]?.jsonObject!!
        assertEquals(7, locations["position"]?.jsonPrimitive?.intOrNull)
        assertNull("no char range / quads on a bookmark", locations["otherLocations"])
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, parsed.type)
        assertEquals("Page 7", parsed.bookmarkTitle)
    }

    @Test
    fun `EPUB rows still emit the EPUB CFI FragmentSelector unchanged`() {
        val entity = AnnotationEntity(
            id = "uuid-epub-1",
            sourceId = "abs1",
            itemId = "epub-item",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:100)",
            color = "yellow",
            note = null,
            textSnippet = "x",
            textBefore = "",
            textAfter = "",
            chapterHref = "ch1.xhtml",
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
        )
        val json = codec.annotationEntityToW3C(entity)
        assertTrue("source uses epub:// scheme",
            json.contains("\"epub://item-epub-item\""))
        assertTrue("FragmentSelector conformsTo IDPF EPUB CFI",
            json.contains("\"http://idpf.org/epub/linking/cfi/epub-cfi.html\""))
        assertTrue("value carries raw epubcfi",
            json.contains("\"epubcfi(/6/4!/4/2,/1:0,/1:100)\""))
        assertFalse("no RFC 3778 conformsTo for EPUB",
            json.contains("rfc3778"))
    }

    // ---- riffle:image Web Annotation body (Task 12) -------------------------

    @Test
    fun `TYPE_IMAGE raster round-trips through Web Annotation body`() {
        val entity = AnnotationEntity(
            id = "uuid-img-raster",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "",
            note = null,
            textSnippet = "Figure 1",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            imageHref = "images/g.png",
            imageSvg = null,
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertTrue("body carries riffle:image type", w3cJson.contains("\"type\":\"riffle:image\""))
        assertTrue("body carries href", w3cJson.contains("\"href\":\"images/g.png\""))
        assertFalse("TYPE_IMAGE body carries no order", w3cJson.contains("\"order\""))

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        assertEquals("Type should be IMAGE", AnnotationEntity.TYPE_IMAGE, parsed.type)
        assertEquals("images/g.png", parsed.imageHref)
        assertNull("svg should be null", parsed.imageSvg)
        assertEquals("Figure 1", parsed.textSnippet)
    }

    @Test
    fun `TYPE_IMAGE svg round-trips through Web Annotation body`() {
        val entity = AnnotationEntity(
            id = "uuid-img-svg",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "",
            note = null,
            textSnippet = "Diagram",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            imageHref = null,
            imageSvg = "<svg>diagram</svg>",
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertTrue("body carries svg", w3cJson.contains("\"svg\":\"<svg>diagram</svg>\""))

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        assertEquals("Type should be IMAGE", AnnotationEntity.TYPE_IMAGE, parsed.type)
        assertNull("href should be null", parsed.imageHref)
        assertEquals("<svg>diagram</svg>", parsed.imageSvg)
        assertEquals("Diagram", parsed.textSnippet)
    }

    @Test
    fun `TYPE_HIGHLIGHT with embeddedFigures round-trips preserving order`() {
        val figures = listOf(
            EmbeddedFigure(href = "b.png", svg = null, caption = "second", order = 1),
            EmbeddedFigure(href = "a.png", svg = null, caption = "first", order = 0),
        )
        val entity = AnnotationEntity(
            id = "uuid-hl-figures",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:100)",
            color = "yellow",
            note = null,
            textSnippet = "surrounding text",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            embeddedFigures = embeddedFiguresListToColumn(figures),
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertTrue("JSON contains text body", w3cJson.contains("\"purpose\":\"highlighting\""))
        assertTrue("JSON contains riffle:image bodies", w3cJson.contains("\"type\":\"riffle:image\""))

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        assertEquals("Type should still be HIGHLIGHT", AnnotationEntity.TYPE_HIGHLIGHT, parsed.type)
        assertEquals("surrounding text", parsed.textSnippet)
        assertEquals(
            "Figures should round-trip sorted by order",
            listOf("a.png", "b.png"),
            parsed.embeddedFigures?.map { it.href },
        )
        assertEquals(listOf(0, 1), parsed.embeddedFigures?.map { it.order })
    }

    @Test
    fun `charOffset and imageBytes round-trip on TYPE_HIGHLIGHT embeddedFigures`() {
        // Regression pin for the 2026-07-14 caption-highlight sync fix. Both extension fields
        // are needed to round-trip: charOffset drives the elided-view figure/caption interleave
        // order, and imageBytes carries the raster the reader inlines when the annotation
        // itself lacks captured bytes. Reverting either encoding half flips this red — an
        // earlier pass shipped only the read half, which silently dropped both fields on push.
        val figure = EmbeddedFigure(
            href = "img.jpg",
            svg = null,
            caption = "cap",
            order = 0,
            imageBytes = "data:image/jpeg;base64,ZZZZ",
            charOffset = 42L,
        )
        val entity = AnnotationEntity(
            id = "uuid-hl-ext",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:100)",
            color = "yellow",
            note = null,
            textSnippet = "cap",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            embeddedFigures = embeddedFiguresListToColumn(listOf(figure)),
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertTrue("outbound JSON must carry charOffset", w3cJson.contains("\"charOffset\":42"))
        assertTrue("outbound JSON must carry imageBytes", w3cJson.contains("\"imageBytes\":\"data:image/jpeg;base64,ZZZZ\""))

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        val f = parsed.embeddedFigures?.single()
        assertEquals(42L, f?.charOffset)
        assertEquals("data:image/jpeg;base64,ZZZZ", f?.imageBytes)
    }

    @Test
    fun `TYPE_IMAGE imageBytes round-trips on the annotation body`() {
        val entity = AnnotationEntity(
            id = "uuid-img-bytes",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_IMAGE,
            cfi = "epubcfi(/6/4!/4/2)",
            color = "",
            note = null,
            textSnippet = "Figure X",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            imageHref = "images/g.png",
            imageBytes = "data:image/png;base64,QQQQ",
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertTrue(
            "TYPE_IMAGE outbound JSON must carry imageBytes as an extension on the riffle:image body",
            w3cJson.contains("\"imageBytes\":\"data:image/png;base64,QQQQ\""),
        )

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        assertEquals("data:image/png;base64,QQQQ", parsed.imageBytes)
    }

    @Test
    fun `TYPE_HIGHLIGHT with null embeddedFigures does not emit riffle_image bodies`() {
        val entity = AnnotationEntity(
            id = "uuid-hl-no-figures",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:100)",
            color = "yellow",
            note = null,
            textSnippet = "plain highlight",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            embeddedFigures = null,
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertFalse("No riffle:image bodies should be emitted", w3cJson.contains("riffle:image"))

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, parsed.type)
        assertNull(parsed.embeddedFigures)
    }

    @Test
    fun `TYPE_HIGHLIGHT with empty embeddedFigures round-trips to null on the entity`() {
        val entity = AnnotationEntity(
            id = "uuid-hl-empty-figures",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:100)",
            color = "yellow",
            note = null,
            textSnippet = "plain highlight",
            chapterHref = "item1",
            createdAt = 1000L,
            updatedAt = 1000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            embeddedFigures = embeddedFiguresListToColumn(emptyList()),
        )

        val w3cJson = codec.annotationEntityToW3C(entity)
        assertFalse("No riffle:image bodies should be emitted", w3cJson.contains("riffle:image"))

        val parsed = codec.w3cToAnnotationEntity(w3cJson)
        assertNull(parsed.embeddedFigures)
    }

    @Test
    fun `riffle_image body with both href and svg prefers href`() {
        // Hand-built JSON (not round-tripped through the encoder, which never emits both href
        // and svg on the same body) — the defensive malformed-input case the decoder must handle.
        val json = """
            {"@context":"http://www.w3.org/ns/anno.jsonld","id":"urn:uuid:uuid-img-both",
             "type":"Annotation","motivation":"describing",
             "target":{"source":"epub://item-item1","selector":[
               {"type":"FragmentSelector","conformsTo":"http://idpf.org/epub/linking/cfi/epub-cfi.html","value":"epubcfi(/6/4!/4/2)"},
               {"type":"TextQuoteSelector","exact":"","prefix":"","suffix":""}
             ]},
             "body":{"type":"riffle:image","value":{"href":"raster.png","svg":"<svg/>","caption":"malformed figure"}},
             "created":"2021-01-01T00:00:00Z","modified":"2021-01-01T00:00:00Z",
             "riffle:originDeviceId":"device-A","riffle:lastModifiedByDeviceId":"device-A",
             "riffle:updatedAt":1000,"riffle:deleted":false}
        """.trimIndent()

        val parsed = codec.w3cToAnnotationEntity(json)

        assertEquals(AnnotationEntity.TYPE_IMAGE, parsed.type)
        assertEquals("raster.png", parsed.imageHref)
        assertNull("svg should be nulled out when href wins", parsed.imageSvg)
        assertEquals("malformed figure", parsed.textSnippet)
    }

    // ADR 0046: TYPE_EMPHASIS rows round-trip through a riffle:emphasis body carrying the
    // encoded styles token. Regression flip: dropping the body encode or the type override on
    // decode would leave the parsed row as TYPE_HIGHLIGHT with a null color — the renderer would
    // paint a phantom no-color highlight instead of applying the emphasis marks.
    @Test
    fun `serializeEmphasis emits a riffle_emphasis body with the styles token`() {
        val entity = AnnotationEntity(
            id = "uuid-emph-1",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_EMPHASIS,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:12)",
            color = "",
            note = null,
            textSnippet = "the key phrase",
            textBefore = "he said ",
            textAfter = " — and then",
            chapterHref = "chap03.xhtml",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            emphasisStyles = "bold,underline",
        )
        val json = codec.annotationEntityToW3C(entity)
        assertTrue("body carries riffle:emphasis type", json.contains("\"type\":\"riffle:emphasis\""))
        assertTrue("body value carries the styles token", json.contains("\"styles\":\"bold,underline\""))
        assertTrue("motivation is commenting so legacy peers don't render phantom highlights (F3)",
            json.contains("\"motivation\":\"commenting\""))
    }

    @Test
    fun `parseEmphasis body flips type to EMPHASIS and populates emphasisStyles`() {
        val json = """
            {"@context":"http://www.w3.org/ns/anno.jsonld","id":"urn:uuid:uuid-emph-2",
             "type":"Annotation","motivation":"commenting",
             "target":{"source":"epub://item-item1","selector":[
               {"type":"FragmentSelector","conformsTo":"http://idpf.org/epub/linking/cfi/epub-cfi.html","value":"epubcfi(/6/2!/4/2,/1:0,/1:5)"},
               {"type":"TextQuoteSelector","exact":"phrase","prefix":"","suffix":""}
             ]},
             "body":{"type":"riffle:emphasis","value":{"styles":"italic,strike"}},
             "created":"2024-01-01T00:00:00Z","modified":"2024-01-01T00:00:00Z",
             "riffle:originDeviceId":"device-B","riffle:lastModifiedByDeviceId":"device-B",
             "riffle:updatedAt":2000,"riffle:deleted":false}
        """.trimIndent()

        val parsed = codec.w3cToAnnotationEntity(json)

        assertEquals(AnnotationEntity.TYPE_EMPHASIS, parsed.type)
        assertEquals("italic,strike", parsed.emphasisStyles)
    }

    @Test
    fun `round-trip preserves emphasis styles across encode→decode`() {
        val entity = AnnotationEntity(
            id = "uuid-emph-rt",
            sourceId = "abs1",
            itemId = "item1",
            type = AnnotationEntity.TYPE_EMPHASIS,
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:6)",
            color = "",
            note = null,
            textSnippet = "phrase",
            textBefore = "",
            textAfter = "",
            chapterHref = "chap01.xhtml",
            createdAt = 1_000_000L,
            updatedAt = 1_000_000L,
            originDeviceId = "device-A",
            lastModifiedByDeviceId = "device-A",
            emphasisStyles = "bold,italic,underline,strike",
        )

        val roundTripped = codec.w3cToAnnotationEntity(codec.annotationEntityToW3C(entity))

        assertEquals(AnnotationEntity.TYPE_EMPHASIS, roundTripped.type)
        assertEquals("bold,italic,underline,strike", roundTripped.emphasisStyles)
    }

    @Test
    fun `riffle_image body with neither href nor svg is skipped`() {
        val json = """
            {"@context":"http://www.w3.org/ns/anno.jsonld","id":"urn:uuid:uuid-img-neither",
             "type":"Annotation","motivation":"describing",
             "target":{"source":"epub://item-item1","selector":[
               {"type":"FragmentSelector","conformsTo":"http://idpf.org/epub/linking/cfi/epub-cfi.html","value":"epubcfi(/6/4!/4/2)"},
               {"type":"TextQuoteSelector","exact":"","prefix":"","suffix":""}
             ]},
             "body":{"type":"riffle:image","value":{"caption":"no source"}},
             "created":"2021-01-01T00:00:00Z","modified":"2021-01-01T00:00:00Z",
             "riffle:originDeviceId":"device-A","riffle:lastModifiedByDeviceId":"device-A",
             "riffle:updatedAt":1000,"riffle:deleted":false}
        """.trimIndent()

        val parsed = codec.w3cToAnnotationEntity(json)

        // No usable riffle:image body survives, so this doesn't resolve to TYPE_IMAGE and no
        // image fields are populated.
        assertNull(parsed.imageHref)
        assertNull(parsed.imageSvg)
        assertNull(parsed.embeddedFigures)
    }
}
