package com.riffle.core.data.localfiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One completed import, tagged so a repeat of the same outcome still reaches the UI. */
data class OpenInImportEvent(val id: Long, val result: OpenInImportResult)

/**
 * Carries "Open in Riffle" outcomes from the OS entry point (an Android `Intent`, an iOS
 * `onOpenURL`) to whatever composition happens to be on screen.
 *
 * It has to be a singleton rather than screen state because the import starts before there is
 * any screen: on both platforms the file arrives with the launch that opened the app. Without
 * this the whole feature is silent — the book either appears in the library a scan later or it
 * does not, and nothing says which.
 */
class OpenInImportFeed {
    private val _events = MutableStateFlow<List<OpenInImportEvent>>(emptyList())
    val events: StateFlow<List<OpenInImportEvent>> = _events.asStateFlow()

    private var nextId = 1L

    fun publish(result: OpenInImportResult) {
        val id = nextId++
        _events.update { it + OpenInImportEvent(id, result) }
    }

    fun consume(id: Long) {
        _events.update { events -> events.filterNot { it.id == id } }
    }
}
