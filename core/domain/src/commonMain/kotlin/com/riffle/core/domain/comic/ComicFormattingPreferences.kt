package com.riffle.core.domain.comic

import com.riffle.core.domain.ReaderTheme

enum class PanelOverflowBehavior { OFF, SPLIT, SMART_SPLIT }

val ComicBackgroundThemeChoices: List<ReaderTheme> = listOf(ReaderTheme.Light, ReaderTheme.Dark, ReaderTheme.Sepia)

fun ReaderTheme.asComicBackgroundTheme(): ReaderTheme = when (this) {
    ReaderTheme.DarkDim -> ReaderTheme.Dark
    else -> this
}

fun ReaderTheme.resolveComicBackgroundTheme(autoResolvedTheme: ReaderTheme): ReaderTheme =
    if (this == ReaderTheme.Auto) autoResolvedTheme.asComicBackgroundTheme() else asComicBackgroundTheme()

data class ComicFormattingPreferences(
    val backgroundTheme: ReaderTheme = ReaderTheme.Dark,
    val panelViewOn: Boolean = false,
    val panelOverflow: PanelOverflowBehavior = PanelOverflowBehavior.SPLIT,
    val panelAnimationSpeedMs: Int = 250,
    val showChapterMap: Boolean = false,
    val showPageProgress: Boolean = false,
)

data class BookComicFormattingOverrides(
    val backgroundTheme: ReaderTheme? = null,
    val panelViewOn: Boolean? = null,
    val panelOverflow: PanelOverflowBehavior? = null,
    val panelAnimationSpeedMs: Int? = null,
    val showChapterMap: Boolean? = null,
    val showPageProgress: Boolean? = null,
) {
    fun applyTo(global: ComicFormattingPreferences) = global.copy(
        backgroundTheme = (backgroundTheme ?: global.backgroundTheme).asComicBackgroundTheme(),
        panelViewOn = panelViewOn ?: global.panelViewOn,
        panelOverflow = panelOverflow ?: global.panelOverflow,
        panelAnimationSpeedMs = panelAnimationSpeedMs ?: global.panelAnimationSpeedMs,
        showChapterMap = showChapterMap ?: global.showChapterMap,
        showPageProgress = showPageProgress ?: global.showPageProgress,
    )

    fun isEmpty() = backgroundTheme == null &&
        panelViewOn == null && panelOverflow == null && panelAnimationSpeedMs == null &&
        showChapterMap == null && showPageProgress == null
}
