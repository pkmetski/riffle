package com.riffle.core.network

import com.riffle.core.network.model.AbsItemResponse
import com.riffle.core.network.model.StorytellerV2BookResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both sides of the identity check (ADR 0028) parse to the same [com.riffle.core.domain.AudiobookFingerprint]
 * shape: Storyteller's ingested-source record and ABS's audio files.
 */
class AudiobookFingerprintParseTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `storyteller v2 maps the ingested-source audiobook to a fingerprint`() {
        val body = """
            {"title":"The Martian","audiobook":{"fileSize":313869927,"duration":39214.464,
             "manifest":{"readingOrder":[{"href":"x.mp3","duration":39214.464}]}}}
        """.trimIndent()
        val fp = json.decodeFromString<StorytellerV2BookResponse>(body).toFingerprint()!!
        assertEquals(313_869_927L, fp.fileSizeBytes)
        assertEquals(39_214.464, fp.durationSec, 0.001)
        assertEquals(listOf(39_214.464), fp.trackDurationsSec)
    }

    @Test
    fun `storyteller v2 with no audiobook yields no fingerprint`() {
        val fp = json.decodeFromString<StorytellerV2BookResponse>("""{"title":"x"}""").toFingerprint()
        assertNull(fp)
    }

    @Test
    fun `abs item maps its audio files to a fingerprint, ordered by index`() {
        val body = """
            {"id":"abc","media":{"duration":8356.0,"audioFiles":[
              {"index":2,"duration":1721.0,"metadata":{"size":200}},
              {"index":1,"duration":2204.0,"metadata":{"size":300}}]}}
        """.trimIndent()
        val fp = json.decodeFromString<AbsItemResponse>(body).audiobookFingerprint()!!
        assertEquals(500L, fp.fileSizeBytes)
        assertEquals(8356.0, fp.durationSec, 0.001)
        assertEquals(listOf(2204.0, 1721.0), fp.trackDurationsSec) // index order, not array order
    }

    @Test
    fun `abs item with no audio yields no fingerprint`() {
        val fp = json.decodeFromString<AbsItemResponse>("""{"id":"abc","media":{}}""").audiobookFingerprint()
        assertNull(fp)
    }
}
