package com.riffle.core.network

import com.riffle.core.network.model.AbsItemResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsAudioTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `builds the range-capable file stream url`() {
        assertEquals(
            "http://abs/api/items/item1/file/7963985",
            AbsAudioUrl.track("http://abs/", "item1", "7963985"),
        )
    }

    @Test
    fun `parses streamable tracks with ino, index-ordered`() {
        val body = """
            {"id":"x","media":{"audioFiles":[
              {"ino":"b","index":2,"duration":1721.0},
              {"ino":"a","index":1,"duration":2204.0}]}}
        """.trimIndent()
        val tracks = json.decodeFromString<AbsItemResponse>(body).audiobookTracks()
        assertEquals(listOf("a", "b"), tracks.map { it.ino })
        assertEquals(listOf(2204.0, 1721.0), tracks.map { it.durationSec })
    }
}
