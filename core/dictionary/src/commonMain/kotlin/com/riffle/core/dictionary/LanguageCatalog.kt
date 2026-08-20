package com.riffle.core.dictionary

private const val ATTRIBUTION =
    "Data from <a href=\"https://en.wiktionary.org\">Wiktionary</a>"
private const val LICENSE_URL = "https://creativecommons.org/licenses/by-sa/3.0/"
private const val BASE_URL = "https://kaikki.org/dictionary"

private fun entry(
    tag: String,
    name: String,
    sizeBytes: Long,
) = LanguageCatalogEntry(
    languageTag = tag,
    displayName = name,
    jsonlUrl = "$BASE_URL/$name/kaikki.org-dictionary-$name.jsonl",
    approximateSizeBytes = sizeBytes,
    attributionHtml = ATTRIBUTION,
    licenseUrl = LICENSE_URL,
)

object LanguageCatalog {
    val all: List<LanguageCatalogEntry> = listOf(
        entry("en", "English",    600_000_000L),
        entry("fr", "French",     150_000_000L),
        entry("de", "German",     180_000_000L),
        entry("es", "Spanish",    150_000_000L),
        entry("it", "Italian",    120_000_000L),
        entry("pt", "Portuguese", 100_000_000L),
        entry("nl", "Dutch",       70_000_000L),
        entry("ru", "Russian",    140_000_000L),
        entry("ja", "Japanese",    60_000_000L),
        entry("zh", "Chinese",    100_000_000L),
        entry("ko", "Korean",      50_000_000L),
        entry("ar", "Arabic",      60_000_000L),
        entry("la", "Latin",       50_000_000L),
        entry("tr", "Turkish",     50_000_000L),
        entry("pl", "Polish",      80_000_000L),
        entry("sv", "Swedish",     50_000_000L),
    )

    fun entryFor(languageTag: String): LanguageCatalogEntry? =
        all.firstOrNull { it.languageTag == languageTag }
}
