package com.riffle.core.catalog.radioes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioEsParserTest {

    private val PODCASTS_PAGE1 = """
        {
          "totalCount": 52224,
          "count": 2,
          "offset": 0,
          "playables": [
            {
              "id": "the-daily",
              "name": "The Daily",
              "author": "The New York Times",
              "categories": ["News"],
              "logo300x300": "https://podcast-images-prod.radio-assets.com/300/the-daily.jpeg",
              "playable": true
            },
            {
              "id": "serial",
              "name": "Serial",
              "author": "Serial Productions",
              "categories": ["True Crime"],
              "logo300x300": "https://podcast-images-prod.radio-assets.com/300/serial.jpeg",
              "playable": true
            }
          ]
        }
    """.trimIndent()

    private val DETAIL = """
        [
          {
            "id": "the-daily",
            "name": "The Daily",
            "author": "The New York Times",
            "categories": ["News"],
            "logo300x300": "https://podcast-images-prod.radio-assets.com/300/the-daily.jpeg",
            "playable": true,
            "description": "This is what the news should sound like."
          }
        ]
    """.trimIndent()

    private val EPISODES = """
        {
          "totalCount": 2,
          "episodes": [
            {
              "id": "the-daily_episode-two_def456",
              "title": "Episode Two",
              "url": "https://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/test/ep2.mp3",
              "duration": 2400,
              "publishDate": 1700086400,
              "contentFormat": "audio/mpeg",
              "description": "<p>Second episode description.</p>"
            },
            {
              "id": "the-daily_episode-one_abc123",
              "title": "Episode One",
              "url": "https://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/test/ep1.mp3",
              "duration": 1800,
              "publishDate": 1700000000,
              "contentFormat": "audio/mpeg",
              "description": "<p>First episode description.</p>"
            }
          ]
        }
    """.trimIndent()

    private val TAGS = """
        {
          "categories": [
            { "systemName": "CATEGORY_NEWS", "name": "News", "slug": "news" },
            { "systemName": "CATEGORY_TRUE_CRIME", "name": "True Crime", "slug": "true-crime" },
            { "systemName": "CATEGORY_COMEDY", "name": "Comedy", "slug": "comedy" }
          ],
          "languages": [
            { "systemName": "LANGUAGE_ENGLISH", "name": "English", "slug": "english" },
            { "systemName": "LANGUAGE_SPANISH", "name": "Spanish", "slug": "spanish" }
          ]
        }
    """.trimIndent()

    private val STATIONS = """
        {
          "totalCount": 2,
          "playables": [
            {
              "id": "cope-madrid",
              "name": "COPE Madrid",
              "description": "Radio informativa de Madrid.",
              "logo300x300": "https://station-images-prod.radio-assets.com/300/cope-madrid.png",
              "topics": ["News"],
              "city": "Madrid",
              "country": "Spain",
              "hasValidStreams": true,
              "streams": [
                { "url": "https://madrid-cope-flucast.flumotion.com/cope/madrid.mp3.m3u", "contentFormat": "audio/aac", "status": "VALID" }
              ],
              "type": "STATION"
            },
            {
              "id": "rock-radio",
              "name": "Rock Radio",
              "description": null,
              "logo300x300": null,
              "topics": ["Rock", "Music"],
              "city": null,
              "country": "Germany",
              "hasValidStreams": true,
              "streams": [
                { "url": "https://streams.rockradio.de/rockradio/mp3-128/stream.mp3", "contentFormat": "audio/mpeg", "status": "VALID" }
              ],
              "type": "STATION"
            }
          ]
        }
    """.trimIndent()

    private val STATION_DETAIL = """
        [
          {
            "id": "cope-madrid",
            "name": "COPE Madrid",
            "description": "Radio informativa de Madrid.",
            "logo300x300": "https://station-images-prod.radio-assets.com/300/cope-madrid.png",
            "topics": ["News"],
            "city": "Madrid",
            "country": "Spain",
            "hasValidStreams": true,
            "streams": [
              { "url": "https://madrid-cope-flucast.flumotion.com/cope/madrid.mp3.m3u", "contentFormat": "audio/aac", "status": "VALID" }
            ],
            "type": "STATION"
          }
        ]
    """.trimIndent()

    private val STATION_TAGS = """
        {
          "languages": [
            { "systemName": "English", "name": "English", "slug": "english" },
            { "systemName": "German", "name": "German", "slug": "german" }
          ],
          "topics": [
            { "systemName": "News", "name": "News", "slug": "news" }
          ],
          "genres": [
            { "systemName": "Pop", "name": "Pop", "slug": "pop" }
          ],
          "countries": [
            { "systemName": "Germany", "name": "Germany", "slug": "germany", "count": 1234 },
            { "systemName": "Spain", "name": "Spain", "slug": "spain", "count": 456 },
            { "systemName": "United States", "name": "United States", "slug": "united-states", "count": 789 }
          ]
        }
    """.trimIndent()

    @Test
    fun `parsePodcasts extracts id name author from playables array`() {
        val result = RadioEsParser.parsePodcasts(PODCASTS_PAGE1)
        assertEquals(2, result.podcasts.size)
        val daily = result.podcasts.first()
        assertEquals("the-daily", daily.id)
        assertEquals("The Daily", daily.name)
        assertEquals("The New York Times", daily.author)
    }

    @Test
    fun `parsePodcasts extracts totalCount from response`() {
        val result = RadioEsParser.parsePodcasts(PODCASTS_PAGE1)
        assertEquals(52224, result.totalCount)
    }

    @Test
    fun `parsePodcasts extracts logo300x300`() {
        val result = RadioEsParser.parsePodcasts(PODCASTS_PAGE1)
        assertEquals(
            "https://podcast-images-prod.radio-assets.com/300/the-daily.jpeg",
            result.podcasts.first().logo300x300,
        )
    }

    @Test
    fun `parsePodcasts extracts categories`() {
        val result = RadioEsParser.parsePodcasts(PODCASTS_PAGE1)
        assertEquals(listOf("News"), result.podcasts.first().categories)
    }

    @Test
    fun `parsePodcasts tolerates missing optional fields`() {
        val body = """{"totalCount":1,"playables":[{"id":"x","name":"X","author":"A"}]}"""
        val result = RadioEsParser.parsePodcasts(body)
        assertEquals(1, result.podcasts.size)
        assertNull(result.podcasts.first().logo300x300)
        assertTrue(result.podcasts.first().categories.isEmpty())
    }

    @Test
    fun `parsePodcasts skips entries missing id`() {
        val body = """{"totalCount":2,"playables":[{"name":"No ID"},{"id":"ok","name":"OK","author":"A"}]}"""
        val result = RadioEsParser.parsePodcasts(body)
        assertEquals(1, result.podcasts.size)
        assertEquals("ok", result.podcasts.first().id)
    }

    @Test
    fun `parsePodcastDetail returns the first playables entry`() {
        val podcast = RadioEsParser.parsePodcastDetail(DETAIL)
        assertNotNull(podcast)
        assertEquals("the-daily", podcast!!.id)
        assertEquals("This is what the news should sound like.", podcast.description)
    }

    @Test
    fun `parsePodcastDetail returns null when array is empty`() {
        val podcast = RadioEsParser.parsePodcastDetail("[]")
        assertNull(podcast)
    }

    @Test
    fun `parseEpisodes extracts id title url duration publishDate`() {
        val result = RadioEsParser.parseEpisodes(EPISODES)
        assertEquals(2, result.episodes.size)
        val ep1 = result.episodes.first { it.id == "the-daily_episode-one_abc123" }
        assertEquals("Episode One", ep1.title)
        assertEquals("https://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/test/ep1.mp3", ep1.url)
        assertEquals(1800, ep1.durationSec)
        assertEquals(1700000000L * 1000L, ep1.publishDateMs)
    }

    @Test
    fun `parseEpisodes extracts contentFormat`() {
        val result = RadioEsParser.parseEpisodes(EPISODES)
        assertEquals("audio/mpeg", result.episodes.first().contentFormat)
    }

    @Test
    fun `parseEpisodes skips entries missing url`() {
        val body = """{"totalCount":2,"episodes":[{"id":"x","title":"X"},{"id":"y","title":"Y","url":"https://host/y.mp3","duration":60,"publishDate":1}]}"""
        val result = RadioEsParser.parseEpisodes(body)
        assertEquals(1, result.episodes.size)
        assertEquals("y", result.episodes.first().id)
    }

    @Test
    fun `parseEpisodes defaults contentFormat to audio slash mpeg when absent`() {
        val body = """{"episodes":[{"id":"z","title":"Z","url":"https://host/z.mp3","duration":30,"publishDate":1}]}"""
        val result = RadioEsParser.parseEpisodes(body)
        assertEquals("audio/mpeg", result.episodes.first().contentFormat)
    }

    @Test
    fun `parseTags extracts categories and languages`() {
        val result = RadioEsParser.parseTags(TAGS)
        assertEquals(3, result.categories.size)
        assertEquals(2, result.languages.size)
        assertEquals("CATEGORY_NEWS", result.categories.first().systemName)
        assertEquals("News", result.categories.first().name)
        assertEquals("news", result.categories.first().slug)
        assertEquals("LANGUAGE_ENGLISH", result.languages.first().systemName)
        assertEquals("english", result.languages.first().slug)
    }

    @Test
    fun `parseTags tolerates empty arrays`() {
        val result = RadioEsParser.parseTags("""{"categories":[],"languages":[]}""")
        assertTrue(result.categories.isEmpty())
        assertTrue(result.languages.isEmpty())
    }

    @Test
    fun `parseStations extracts id name topics city country`() {
        val result = RadioEsParser.parseStations(STATIONS)
        assertEquals(2, result.stations.size)
        val cope = result.stations.first { it.id == "cope-madrid" }
        assertEquals("COPE Madrid", cope.name)
        assertEquals("Radio informativa de Madrid.", cope.description)
        assertEquals("https://station-images-prod.radio-assets.com/300/cope-madrid.png", cope.logo300x300)
        assertEquals(listOf("News"), cope.topics)
        assertEquals("Madrid", cope.city)
        assertEquals("Spain", cope.country)
    }

    @Test
    fun `parseStations extracts first VALID stream url`() {
        val result = RadioEsParser.parseStations(STATIONS)
        val cope = result.stations.first { it.id == "cope-madrid" }
        assertEquals("https://madrid-cope-flucast.flumotion.com/cope/madrid.mp3.m3u", cope.streamUrl)
        assertEquals("audio/aac", cope.streamFormat)
    }

    @Test
    fun `parseStationDetail returns station from bare array`() {
        val station = RadioEsParser.parseStationDetail(STATION_DETAIL)
        assertNotNull(station)
        assertEquals("cope-madrid", station!!.id)
        assertEquals("COPE Madrid", station.name)
    }

    @Test
    fun `parseStationDetail returns null on empty array`() {
        val station = RadioEsParser.parseStationDetail("[]")
        assertNull(station)
    }

    @Test
    fun `parseStations skips stations with no stream url`() {
        val body = """{"totalCount":1,"playables":[{"id":"x","name":"No Stream","topics":[],"streams":[]}]}"""
        val result = RadioEsParser.parseStations(body)
        assertEquals(1, result.stations.size)
        assertNull(result.stations.first().streamUrl)
    }

    @Test
    fun `parseStationCountries extracts country list from stations tags body`() {
        val countries = RadioEsParser.parseStationCountries(STATION_TAGS)
        assertEquals(3, countries.size)
        val germany = countries.first { it.slug == "germany" }
        assertEquals("Germany", germany.systemName)
        assertEquals("Germany", germany.name)
        val us = countries.first { it.slug == "united-states" }
        assertEquals("United States", us.systemName)
    }

    @Test
    fun `parseStationCountries tolerates missing countries key`() {
        val countries = RadioEsParser.parseStationCountries("""{"languages":[],"topics":[]}""")
        assertTrue(countries.isEmpty())
    }

    @Test
    fun `parseTags skips tag entries missing systemName`() {
        val body = """{"categories":[{"name":"NoSystemName"},{"systemName":"CAT_OK","name":"OK"}],"languages":[]}"""
        val result = RadioEsParser.parseTags(body)
        assertEquals(1, result.categories.size)
        assertEquals("CAT_OK", result.categories.first().systemName)
    }
}
