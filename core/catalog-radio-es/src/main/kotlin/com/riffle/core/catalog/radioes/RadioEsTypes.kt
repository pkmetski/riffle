package com.riffle.core.catalog.radioes

internal data class RadioEsPodcast(
    val id: String,
    val name: String,
    val author: String,
    val categories: List<String>,
    val logo300x300: String?,
    val playable: Boolean,
    val description: String?,
)

internal data class RadioEsEpisode(
    val id: String,
    val title: String,
    val url: String,
    val durationSec: Int,
    val publishDateMs: Long,
    val description: String?,
    val contentFormat: String,
)

internal data class RadioEsCategoryTag(
    val systemName: String,
    val name: String,
)

internal data class RadioEsLanguageTag(
    val systemName: String,
    val name: String,
)

internal data class RadioEsPodcastsResult(
    val podcasts: List<RadioEsPodcast>,
    val totalCount: Int,
)

internal data class RadioEsEpisodesResult(
    val episodes: List<RadioEsEpisode>,
    val totalCount: Int,
)

internal data class RadioEsTagsResult(
    val categories: List<RadioEsCategoryTag>,
    val languages: List<RadioEsLanguageTag>,
)
