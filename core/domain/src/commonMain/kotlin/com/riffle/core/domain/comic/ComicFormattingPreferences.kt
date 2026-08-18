package com.riffle.core.domain.comic

enum class PanelOverflowBehavior { OFF, SPLIT, SMART_SPLIT }

data class ComicFormattingPreferences(
    val panelViewOn: Boolean = false,
    val panelOverflow: PanelOverflowBehavior = PanelOverflowBehavior.SPLIT,
    val panelAnimationSpeedMs: Int = 250,
    val showChapterMap: Boolean = true,
)

data class BookComicFormattingOverrides(
    val panelViewOn: Boolean? = null,
    val panelOverflow: PanelOverflowBehavior? = null,
    val panelAnimationSpeedMs: Int? = null,
    val showChapterMap: Boolean? = null,
) {
    fun applyTo(global: ComicFormattingPreferences) = global.copy(
        panelViewOn = panelViewOn ?: global.panelViewOn,
        panelOverflow = panelOverflow ?: global.panelOverflow,
        panelAnimationSpeedMs = panelAnimationSpeedMs ?: global.panelAnimationSpeedMs,
        showChapterMap = showChapterMap ?: global.showChapterMap,
    )

    fun isEmpty() = panelViewOn == null && panelOverflow == null && panelAnimationSpeedMs == null &&
        showChapterMap == null
}
