package com.riffle.app.feature.audiobook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the pre-warmed audiobook overlay lifecycle.
 *
 * [signal] is emitted by [com.riffle.app.feature.reader.EpubReaderViewModel] when the user
 * completes the swipe-up gesture; the pre-warmed [AudiobookPlayerViewModel] collects it to
 * activate (prepare controller + start playing from the given position).
 *
 * [dismiss] is emitted when the user presses back on the overlay without a swipe-down handoff;
 * [AudiobookPlayerViewModel] pauses the controller so audiobook audio stops while the reader
 * is visible.
 *
 * Both are [StateFlow] so the signal is never missed even if the VM's collector hasn't started
 * yet when the event fires (the collector sees the current non-null value immediately).
 */
@Singleton
class AudiobookHandoffState @Inject constructor() {
    private val _pendingHandoff = MutableStateFlow<HandoffSignal?>(null)
    val pendingHandoff: StateFlow<HandoffSignal?> = _pendingHandoff.asStateFlow()

    private val _pendingDismiss = MutableStateFlow<String?>(null)
    val pendingDismiss: StateFlow<String?> = _pendingDismiss.asStateFlow()

    fun signal(itemId: String, atSec: Double) {
        _pendingHandoff.value = HandoffSignal(itemId, atSec)
    }

    fun consumeHandoff() {
        _pendingHandoff.value = null
    }

    fun dismiss(itemId: String) {
        _pendingDismiss.value = itemId
    }

    fun consumeDismiss() {
        _pendingDismiss.value = null
    }

    data class HandoffSignal(val itemId: String, val atSec: Double)
}
