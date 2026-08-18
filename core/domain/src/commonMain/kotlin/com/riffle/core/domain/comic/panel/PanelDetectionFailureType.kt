package com.riffle.core.domain.comic.panel

enum class PanelDetectionFailureType(val label: String, val githubLabel: String) {
    MissedPanel("Missed panel", "panel-missed"),
    MergedPanels("Merged panels", "panel-merged"),
    WrongPanelCount("Wrong panel count", "panel-wrong-count"),
    FalsePanel("False panel", "panel-false"),
    FellBackToFullPage("Fell back to full page", "panel-fallback"),
    CutPanelCutOff("Cut panel cut off", "panel-cut-off"),
}
