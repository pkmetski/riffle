package com.riffle.core.domain

import java.io.File

interface JvmReadaloudAudioRepository : ReadaloudAudioRepository, ReadaloudBundleReader {
    override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack?
    fun bundleFile(sourceId: String, itemId: String): File?

    /** [ReadaloudBundleReader] view of [bundleFile], so commonMain callers need no java.io.File. */
    override fun bundlePath(sourceId: String, itemId: String): String? =
        bundleFile(sourceId, itemId)?.absolutePath
}
