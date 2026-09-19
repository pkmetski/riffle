package com.riffle.core.domain

/**
 * Read access to a downloaded Storyteller readaloud bundle (ADR 0027), in the platform-neutral
 * terms [com.riffle.core.data.StorytellerBundleAudiobookSource] needs: where the bundle is, and
 * what its Media Overlay timeline says.
 *
 * Exists so the bundle-as-audiobook mapping can live in commonMain. Android's
 * `ReadaloudAudioRepositoryImpl` reads the zip with `java.util.zip`, iOS's
 * `IosReadaloudAudioRepositoryImpl` with `IosZipArchive` — but both hand back the same
 * [ReadaloudTrack] and an absolute path, so everything above this seam is shared.
 */
interface ReadaloudBundleReader {
    /** Absolute path of the downloaded-or-cached bundle, or null when it isn't stored locally. */
    fun bundlePath(sourceId: String, itemId: String): String?

    /** The bundle's Media Overlay timeline, or null when absent/unparseable/empty. */
    suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack?
}
