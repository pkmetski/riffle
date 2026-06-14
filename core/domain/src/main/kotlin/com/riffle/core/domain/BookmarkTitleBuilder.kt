package com.riffle.core.domain

/** Builds the pre-filled (editable) default title for a new bookmark from the timeline + position. */
object BookmarkTitleBuilder {

    fun defaultTitle(timeline: AudiobookTimeline, positionSec: Double): String {
        val chapter = timeline.chapterAt(positionSec) ?: return clock(positionSec)
        val offset = (positionSec - chapter.startSec).coerceAtLeast(0.0)
        val label = chapter.title.trim().ifEmpty { "Chapter ${chapter.index + 1}" }
        return "$label · ${clock(offset)}"
    }

    // mm:ss under an hour, h:mm:ss otherwise.
    private fun clock(sec: Double): String {
        val total = sec.toLong()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
