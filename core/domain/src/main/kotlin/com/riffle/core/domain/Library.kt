package com.riffle.core.domain

data class Library(
    val id: String,
    val name: String,
    val mediaType: String,
    val isUnsupported: Boolean,
)

/**
 * Media type of the local-only "Readalouds" library row that namespaces a Storyteller Server's
 * readaloud books as matcher input. Per ADR 0026 this row is never browsable — it must be excluded
 * from any Server Switcher or library picker.
 */
const val READALOUD_MEDIA_TYPE = "readaloud"

val Library.isReadaloud: Boolean get() = mediaType == READALOUD_MEDIA_TYPE
