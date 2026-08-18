package com.riffle.core.domain.comic.panel

enum class PanelDetectionFailureType(val label: String) {
    MissedPanel("Missed panel"),
    MergedPanels("Merged panels"),
    WrongPanelCount("Wrong panel count"),
    FalsePanel("False panel"),
    FellBackToFullPage("Fell back to full page"),
    CutPanelCutOff("Panel cut off"),
}
