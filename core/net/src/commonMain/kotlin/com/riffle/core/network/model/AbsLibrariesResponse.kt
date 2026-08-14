package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsLibrariesResponse(val libraries: List<AbsLibraryDto>) {
    @Serializable
    data class AbsLibraryDto(
        val id: String,
        val name: String,
        val mediaType: String,
        val folders: List<AbsLibraryFolderDto> = emptyList(),
        val settings: AbsLibrarySettingsDto = AbsLibrarySettingsDto(),
    )

    @Serializable
    data class AbsLibraryFolderDto(
        val id: String,
        val fullPath: String,
    )

    @Serializable
    data class AbsLibrarySettingsDto(val audiobooksOnly: Boolean = false)
}
