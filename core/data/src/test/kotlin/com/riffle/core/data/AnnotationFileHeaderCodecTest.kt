package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationFileHeaderCodecTest {

    private val meta = AnnotationFileHeader(
        deviceId = "dev-1",
        label = "Phone A",
        lastSeenAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun `buildFileBody with no annotations renders just the header`() {
        val body = AnnotationFileHeaderCodec.buildFileBody(meta, annotationJsonStrings = emptyList())
        val extracted = AnnotationFileHeaderCodec.extractHeader(body)
        assertEquals(meta, extracted)
    }

    @Test
    fun `buildFileBody preserves annotation records in order after the header`() {
        val body = AnnotationFileHeaderCodec.buildFileBody(
            meta,
            annotationJsonStrings = listOf("""{"id":"a"}""", """{"id":"b"}"""),
        )
        val extracted = AnnotationFileHeaderCodec.extractHeader(body)
        assertEquals(meta, extracted)
        assertTrue(body.contains("\"id\":\"a\""))
        assertTrue(body.contains("\"id\":\"b\""))
    }

    @Test
    fun `replaceHeader swaps the header without losing annotations`() {
        val original = AnnotationFileHeaderCodec.buildFileBody(
            meta,
            annotationJsonStrings = listOf("""{"id":"x"}"""),
        )
        val newMeta = meta.copy(label = "Renamed", lastSeenAt = "2026-02-02T02:02:02Z")
        val rewritten = AnnotationFileHeaderCodec.replaceHeader(original, newMeta)
        val extracted = AnnotationFileHeaderCodec.extractHeader(rewritten)
        assertEquals(newMeta, extracted)
        assertTrue(rewritten.contains("\"id\":\"x\""))
    }

    @Test
    fun `extractHeader returns null for legacy header-less files`() {
        val legacy = """[{"id":"a"},{"id":"b"}]"""
        assertNull(AnnotationFileHeaderCodec.extractHeader(legacy))
    }

    @Test
    fun `replaceHeader on a legacy file inserts a header`() {
        val legacy = """[{"id":"a"}]"""
        val rewritten = AnnotationFileHeaderCodec.replaceHeader(legacy, meta)
        assertEquals(meta, AnnotationFileHeaderCodec.extractHeader(rewritten))
        assertTrue(rewritten.contains("\"id\":\"a\""))
    }

    @Test
    fun `malformed input is returned unchanged by replaceHeader and null by extractHeader`() {
        val bogus = "not json {{"
        assertNull(AnnotationFileHeaderCodec.extractHeader(bogus))
        assertEquals(bogus, AnnotationFileHeaderCodec.replaceHeader(bogus, meta))
    }

    @Test
    fun `bookTitle round-trips through encode and extract`() {
        val withTitle = meta.copy(bookTitle = "Project Hail Mary")
        val body = AnnotationFileHeaderCodec.buildFileBody(withTitle, annotationJsonStrings = emptyList())
        assertTrue(body.contains("\"bookTitle\":\"Project Hail Mary\""))
        assertEquals(withTitle, AnnotationFileHeaderCodec.extractHeader(body))
    }

    @Test
    fun `username round-trips through encode and extract`() {
        val withUser = meta.copy(username = "alice")
        val body = AnnotationFileHeaderCodec.buildFileBody(withUser, annotationJsonStrings = emptyList())
        assertTrue(body.contains("\"username\":\"alice\""))
        assertEquals(withUser, AnnotationFileHeaderCodec.extractHeader(body))
    }

    @Test
    fun `username is omitted when null and parses back as null`() {
        val body = AnnotationFileHeaderCodec.buildFileBody(meta, annotationJsonStrings = emptyList())
        assertTrue(!body.contains("username"))
        val extracted = AnnotationFileHeaderCodec.extractHeader(body)
        assertNull(extracted?.username)
    }

    @Test
    fun `legacy riffle DeviceMeta header is still recognised on read`() {
        // Hand-built file body using the pre-rename wire marker. When we eventually delete the
        // legacy alias in AnnotationFileHeaderCodec, this test fails — that's the signal to
        // confirm every share has rolled forward before dropping the compatibility path.
        val legacyBody = """
            [
              {"type":"riffle:DeviceMeta","deviceId":"legacy-A","label":"Old","lastSeenAt":"2025-01-01T00:00:00Z","username":"alice"},
              {"id":"ann-1"}
            ]
        """.trimIndent()
        val header = AnnotationFileHeaderCodec.extractHeader(legacyBody)
        assertEquals(AnnotationFileHeader("legacy-A", "Old", "2025-01-01T00:00:00Z", "alice"), header)
    }

    @Test
    fun `replaceHeader on a legacy-marker file leaves a single new-marker header`() {
        // Mid-transition: another device wrote the file with the old marker; we rename on this
        // device and rewrite. The legacy header must be removed, not stacked alongside the new one.
        val legacyBody = """[{"type":"riffle:DeviceMeta","deviceId":"A","label":"Old","lastSeenAt":"2025-01-01T00:00:00Z"},{"id":"x"}]"""
        val rewritten = AnnotationFileHeaderCodec.replaceHeader(legacyBody, meta.copy(deviceId = "A"))
        assertTrue("new marker must be present", rewritten.contains("riffle:FileHeader"))
        assertTrue("legacy marker must be gone", !rewritten.contains("riffle:DeviceMeta"))
        assertTrue("annotation records survive", rewritten.contains("\"id\":\"x\""))
    }

    @Test
    fun `blank username is treated as absent on both sides`() {
        val blank = meta.copy(username = "")
        val body = AnnotationFileHeaderCodec.buildFileBody(blank, annotationJsonStrings = emptyList())
        assertTrue(!body.contains("username"))
        assertNull(AnnotationFileHeaderCodec.extractHeader(body)?.username)
    }
}
