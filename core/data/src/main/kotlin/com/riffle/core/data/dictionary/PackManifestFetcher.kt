package com.riffle.core.data.dictionary

import com.riffle.core.dictionary.PackInfo
import com.riffle.core.dictionary.PackManifest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.IOException

class PackManifestFetcher(
    private val httpClient: HttpClient,
    private val manifestUrl: String,
) {
    suspend fun fetch(): PackManifest {
        val response = httpClient.get(manifestUrl)
        if (!response.status.isSuccess()) {
            throw IOException("Manifest fetch failed: HTTP ${response.status.value}")
        }
        val json = response.body<PackManifestJson>()
        return PackManifest(
            version = json.version,
            packs = json.packs.map { p ->
                PackInfo(
                    languageTag = p.languageTag,
                    packVersion = p.packVersion,
                    downloadUrl = p.downloadUrl,
                    sha256 = p.sha256,
                    sizeBytes = p.sizeBytes,
                    attributionHtml = p.attributionHtml,
                    licenseUrl = p.licenseUrl,
                )
            },
        )
    }
}
