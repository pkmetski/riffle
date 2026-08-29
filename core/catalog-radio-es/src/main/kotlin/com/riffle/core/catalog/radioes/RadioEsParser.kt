package com.riffle.core.catalog.radioes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object RadioEsParser {

    const val BASE: String = "https://prod.radio-api.net"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parsePodcasts(body: String): RadioEsPodcastsResult {
        val root = json.parseToJsonElement(body).jsonObject
        val playables = root["playables"]?.jsonArray ?: emptyJsonArray()
        val totalCount = root["totalCount"]?.jsonPrimitive?.intOrNull ?: playables.size
        val podcasts = playables.mapNotNull { parsePodcast(it) }
        return RadioEsPodcastsResult(podcasts = podcasts, totalCount = totalCount)
    }

    fun parsePodcastDetail(body: String): RadioEsPodcast? {
        val root = json.parseToJsonElement(body).jsonObject
        val arr = root["playables"]?.jsonArray ?: root["podcasts"]?.jsonArray
        val obj = arr?.firstOrNull()?.jsonObject ?: return null
        return parsePodcast(obj)
    }

    fun parseEpisodes(body: String): RadioEsEpisodesResult {
        val root = json.parseToJsonElement(body).jsonObject
        val episodesArr = root["episodes"]?.jsonArray ?: emptyJsonArray()
        val totalCount = root["totalCount"]?.jsonPrimitive?.intOrNull ?: episodesArr.size
        val episodes = episodesArr.mapNotNull { parseEpisode(it) }
        return RadioEsEpisodesResult(episodes = episodes, totalCount = totalCount)
    }

    fun parseTags(body: String): RadioEsTagsResult {
        val root = json.parseToJsonElement(body).jsonObject
        val cats = root["categories"]?.jsonArray?.mapNotNull { parseTag(it) } ?: emptyList()
        val langs = root["languages"]?.jsonArray?.mapNotNull { parseLangTag(it) } ?: emptyList()
        return RadioEsTagsResult(categories = cats, languages = langs)
    }

    private fun parsePodcast(element: JsonElement): RadioEsPodcast? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { return null }
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val author = obj["author"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val categories = obj["categories"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()
        val logo = obj["logo300x300"]?.jsonPrimitive?.contentOrNull
            ?: obj["logo175x175"]?.jsonPrimitive?.contentOrNull
        val playable = obj["playable"]?.jsonPrimitive?.booleanOrNull ?: true
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        return RadioEsPodcast(
            id = id,
            name = name,
            author = author,
            categories = categories,
            logo300x300 = logo,
            playable = playable,
            description = description,
        )
    }

    private fun parseEpisode(element: JsonElement): RadioEsEpisode? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { return null }
        val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { return null }
        val durationSec = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0
        val publishDateSec = obj["publishDate"]?.jsonPrimitive?.longOrNull ?: 0L
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val contentFormat = obj["contentFormat"]?.jsonPrimitive?.contentOrNull ?: "audio/mpeg"
        return RadioEsEpisode(
            id = id,
            title = title,
            url = url,
            durationSec = durationSec,
            publishDateMs = publishDateSec * 1000L,
            description = description,
            contentFormat = contentFormat,
        )
    }

    private fun parseTag(element: JsonElement): RadioEsCategoryTag? {
        val obj = element as? JsonObject ?: return null
        val systemName = obj["systemName"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { return null }
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return RadioEsCategoryTag(systemName = systemName, name = name, slug = slug)
    }

    private fun parseLangTag(element: JsonElement): RadioEsLanguageTag? {
        val obj = element as? JsonObject ?: return null
        val systemName = obj["systemName"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { return null }
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return RadioEsLanguageTag(systemName = systemName, name = name)
    }

    private fun emptyJsonArray() = JsonArray(emptyList())
}
