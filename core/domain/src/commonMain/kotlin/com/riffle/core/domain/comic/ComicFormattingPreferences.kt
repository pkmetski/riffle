package com.riffle.core.domain.comic

enum class PanelOverflowBehavior { OFF, SPLIT, AUTO_ROTATE }

data class ComicFormattingPreferences(
    val panelViewOn: Boolean = false,
    val panelOverflow: PanelOverflowBehavior = PanelOverflowBehavior.SPLIT,
)

data class BookComicFormattingOverrides(
    val panelViewOn: Boolean? = null,
    val panelOverflow: PanelOverflowBehavior? = null,
) {
    fun applyTo(global: ComicFormattingPreferences) = global.copy(
        panelViewOn = panelViewOn ?: global.panelViewOn,
        panelOverflow = panelOverflow ?: global.panelOverflow,
    )

    fun isEmpty() = panelViewOn == null && panelOverflow == null
}
