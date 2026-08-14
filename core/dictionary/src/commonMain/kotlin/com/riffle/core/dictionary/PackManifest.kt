package com.riffle.core.dictionary

data class PackManifest(
    val version: Int,
    val packs: List<PackInfo>,
)
