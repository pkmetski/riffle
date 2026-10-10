package com.riffle.feature.reader.ui

val testAnnotationSheetLabels = AnnotationSheetLabels(
    noHighlightColor = "No highlight color",
    selectedSuffix = ", selected",
    highlightSuffix = " highlight",
    emphasis = "Emphasis ",
    activeSuffix = ", active",
    note = "Note",
    addNote = "Add note",
    edit = "Edit",
    save = "Save",
    remove = "Remove",
    cancel = "Cancel",
    delete = "Delete annotation",
    bookmark = "Bookmark this page",
    removeBookmark = "Remove bookmark",
)

val testSpeedHudLabels = SpeedHudLabels(
    slower = "Slower",
    faster = "Faster",
    wordsPerMinute = "%1\$d wpm",
    pause = "Pause auto-scroll",
    resume = "Resume auto-scroll",
)

val testCadenceHudLabels = SpeedHudLabels(
    slower = "Slower",
    faster = "Faster",
    wordsPerMinute = "%1\$d wpm",
    pause = "Pause cadence",
    resume = "Resume cadence",
)

val testChapterMapProgressLabelTemplates = ChapterMapProgressLabelTemplates(
    chapterCount = "Chapter %1\$d of %2\$d",
    durationMinutes = "%1\$d min",
    durationHoursMinutes = "%1\$dh %2\$dmin",
    chapterRemainingExact = "%1\$s chapter",
    chapterRemainingEstimated = "~%1\$s chapter",
    chapterRemainingLessThanMinute = "< 1min chapter",
    bookRemainingExact = "%1\$s total",
    bookRemainingEstimated = "~%1\$s total",
    bookRemainingLessThanMinute = "< 1min total",
    readingProgressValue = "Reading progress: %1\$s",
    currentChapterValue = "Current chapter: %1\$s",
    totalProgressValue = "Total progress: %1\$s",
    activeRailSegmentProgress = "Active rail segment: %1\$s. Progress %2\$d%%",
)
