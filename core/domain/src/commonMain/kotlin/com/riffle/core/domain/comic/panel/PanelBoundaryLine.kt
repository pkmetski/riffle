package com.riffle.core.domain.comic.panel

/** A user-drawn dividing line between panels, in source image pixel coordinates. */
data class PanelBoundaryLine(val x1: Int, val y1: Int, val x2: Int, val y2: Int)
