package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationFileHeaderCodecTest {

    private val meta = AnnotationFileHeader(deviceId = "dev-1")

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
    fun `extractHeader returns null for legacy header-less files`() {
        val legacy = """[{"id":"a"},{"id":"b"}]"""
        assertNull(AnnotationFileHeaderCodec.extractHeader(legacy))
    }

    @Test
    fun `extractHeader returns null on malformed input`() {
        assertNull(AnnotationFileHeaderCodec.extractHeader("not json {{"))
    }

    @Test
    fun `bookTitle round-trips through encode and extract`() {
        val withTitle = meta.copy(bookTitle = "Project Hail Mary")
        val body = AnnotationFileHeaderCodec.buildFileBody(withTitle, annotationJsonStrings = emptyList())
        assertTrue(body.contains("\"bookTitle\":\"Project Hail Mary\""))
        assertEquals(withTitle, AnnotationFileHeaderCodec.extractHeader(body))
    }

    @Test
    fun `device-scoped fields are not emitted on encode`() {
        // The slim file header carries only book-scoped data. label/lastSeenAt/username live in
        // the per-device sentinel ([AnnotationDeviceMeta]); they must not be re-emitted here even
        // by accident — duplicating them would re-introduce the inconsistency the split fixed.
        val body = AnnotationFileHeaderCodec.buildFileBody(meta.copy(bookTitle = "Title"), emptyList())
        assertTrue("no label", !body.contains("\"label\""))
        assertTrue("no lastSeenAt", !body.contains("\"lastSeenAt\""))
        assertTrue("no username", !body.contains("\"username\""))
    }

    @Test
    fun `legacy header carrying device-scoped fields is read as the slim shape`() {
        // Pre-split file: the embedded header still has label/lastSeenAt/username/bookTitle.
        // extractHeader must ignore the device-scoped extras and surface only deviceId + bookTitle.
        val legacyBody = """
            [
              {"type":"riffle:FileHeader","deviceId":"legacy-A","label":"Old","lastSeenAt":"2025-01-01T00:00:00Z","username":"alice","bookTitle":"Dune"},
              {"id":"ann-1"}
            ]
        """.trimIndent()
        val header = AnnotationFileHeaderCodec.extractHeader(legacyBody)
        assertEquals(AnnotationFileHeader(deviceId = "legacy-A", bookTitle = "Dune"), header)
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
        assertEquals(AnnotationFileHeader(deviceId = "legacy-A"), header)
    }
}
