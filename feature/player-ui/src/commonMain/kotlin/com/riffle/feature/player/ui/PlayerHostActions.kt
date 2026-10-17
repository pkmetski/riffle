package com.riffle.feature.player.ui

data class PlayerHostActions(
    val onBack: () -> Unit,
    val onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit,
    val onSwitchToReadaloud: ((ebookItemId: String, atSec: Double) -> Unit)? = null,
)

fun PlayerHostActions.probeFields(): List<Pair<String, Any>> = buildList {
    add("onBack" to onBack)
    add("onPlaylistAdvance" to onPlaylistAdvance)
    onSwitchToReadaloud?.let { add("onSwitchToReadaloud" to it) }
}
