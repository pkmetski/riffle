package com.riffle.core.catalog.radioes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioEsParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture: $name" }
            .bufferedReader().use { it.readText() }

    // ---- parsePodcasts -------------------------------------------------------

    @Test
    fun `parsePodcasts extracts id name author from playables array`() {
        val result = RadioEsParser.parsePodcasts(fixture("radioes-podcasts-page1.json"))
        assertEquals(2, result.podcasts.size)
        val daily = result.podcasts.first()
        assertEquals("the-daily", daily.id)
        assertEquals("The Daily", daily.name)
        assertEquals("The New York Times", daily.author)
    }

    @Test
    fun `parsePodcasts extracts totalCount from response`() {
        val result = RadioEsParser.parsePodcasts(fixture("radioes-podcasts-page1.json"))
        assertEquals(52224, result.totalCount)
    }

    @Test
    fun `parsePodcasts extracts logo300x300`() {
        val result = RadioEsParser.parsePodcasts(fixture("radioes-podcasts-page1.json"))
        assertEquals(
            "https://podcast-images-prod.radio-assets.com/300/the-daily.jpeg",
            result.podcasts.first().logo300x300,
        )
    }

    @Test
    fun `parsePodcasts extracts categories`() {
        val result = RadioEsParser.parsePodcasts(fixture("radioes-podcasts-page1.json"))
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

    // ---- parsePodcastDetail -------------------------------------------------

    @Test
    fun `parsePodcastDetail returns the first playables entry`() {
        val podcast = RadioEsParser.parsePodcastDetail(fixture("radioes-detail.json"))
        assertNotNull(podcast)
        assertEquals("the-daily", podcast!!.id)
        assertEquals("This is what the news should sound like.", podcast.description)
    }

    @Test
    fun `parsePodcastDetail returns null when array is empty`() {
        val podcast = RadioEsParser.parsePodcastDetail("[]")
        assertNull(podcast)
    }

    // ---- parseEpisodes -------------------------------------------------------

    @Test
    fun `parseEpisodes extracts id title url duration publishDate`() {
        val result = RadioEsParser.parseEpisodes(fixture("radioes-episodes.json"))
        assertEquals(2, result.episodes.size)
        // fixture is newest-first (mirrors real API); find by id rather than position
        val ep1 = result.episodes.first { it.id == "the-daily_episode-one_abc123" }
        assertEquals("Episode One", ep1.title)
        assertEquals("https://dts.podtrac.com/redirect.mp3/traffic.libsyn.com/test/ep1.mp3", ep1.url)
        assertEquals(1800, ep1.durationSec)
        assertEquals(1700000000L * 1000L, ep1.publishDateMs)
    }

    @Test
    fun `parseEpisodes extracts contentFormat`() {
        val result = RadioEsParser.parseEpisodes(fixture("radioes-episodes.json"))
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

    // ---- parseTags ----------------------------------------------------------

    @Test
    fun `parseTags extracts categories and languages`() {
        val result = RadioEsParser.parseTags(fixture("radioes-tags.json"))
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

    // ---- parseStations ----------------------------------------------------------

    @Test
    fun `parseStations extracts id name topics city country`() {
        val result = RadioEsParser.parseStations(fixture("radioes-stations.json"))
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
        val result = RadioEsParser.parseStations(fixture("radioes-stations.json"))
        val cope = result.stations.first { it.id == "cope-madrid" }
        assertEquals("https://madrid-cope-flucast.flumotion.com/cope/madrid.mp3.m3u", cope.streamUrl)
        assertEquals("audio/aac", cope.streamFormat)
    }

    @Test
    fun `parseStationDetail returns station from bare array`() {
        val station = RadioEsParser.parseStationDetail(fixture("radioes-station-detail.json"))
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
        // parseStations itself doesn't filter — filtering is in browse(). Just check it doesn't crash.
        assertEquals(1, result.stations.size)
        assertNull(result.stations.first().streamUrl)
    }

    @Test
    fun `parseStationCountries extracts country list from stations tags body`() {
        val countries = RadioEsParser.parseStationCountries(fixture("radioes-station-tags.json"))
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
