package com.riffle.feature.reader.ui

data class ReaderHostActions(
    val onBack: () -> Unit,
    val onSwitchToAudiobook: (() -> Unit)? = null,
    val onOpenInBook: ((locatorJson: String) -> Unit)? = null,
)

fun ReaderHostActions.probeFields(): List<Pair<String, Any>> = buildList {
    add("onBack" to onBack)
    onSwitchToAudiobook?.let { add("onSwitchToAudiobook" to it) }
    onOpenInBook?.let { add("onOpenInBook" to it) }
}
