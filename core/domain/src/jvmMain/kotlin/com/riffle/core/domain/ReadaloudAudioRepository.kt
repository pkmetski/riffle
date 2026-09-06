package com.riffle.core.domain

import java.io.File

interface JvmReadaloudAudioRepository : ReadaloudAudioRepository {
    suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack?
    fun bundleFile(sourceId: String, itemId: String): File?
}
