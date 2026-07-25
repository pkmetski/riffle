package com.riffle.core.domain

data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)
