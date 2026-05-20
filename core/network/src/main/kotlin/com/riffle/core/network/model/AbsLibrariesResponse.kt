package com.riffle.core.network.model

import kotlinx.serialization.Serializable

@Serializable
internal data class AbsLibrariesResponse(val libraries: List<AbsLibraryDto>) {
    @Serializable
    data class AbsLibraryDto(val id: String, val name: String, val mediaType: String)
}
