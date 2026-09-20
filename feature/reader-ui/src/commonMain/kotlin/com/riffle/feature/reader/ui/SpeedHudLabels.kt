package com.riffle.feature.reader.ui

/**
 * The strings the speed HUD pills need, supplied by the host — the same arrangement the chapter
 * map uses ([ChapterMapProgressLabelTemplates]) and for the same reason: the pill is shared, the
 * string catalogue is not. `:app` fills this from `res/values*`, `:shared` from [English].
 *
 * [wordsPerMinute] is a positional template expanded by [formatTemplate].
 */
data class SpeedHudLabels(
    /** `"Slower"` */
    val slower: String,
    /** `"Faster"` */
    val faster: String,
    /** `"%1$d wpm"` */
    val wordsPerMinute: String,
    val pause: String,
    val resume: String,
) {
    companion object {
        /** Verbatim from `app/src/main/res/values/strings.xml`, for the iOS host. */
        val English = SpeedHudLabels(
            slower = "Slower",
            faster = "Faster",
            wordsPerMinute = "%1\$d wpm",
            pause = "Pause auto-scroll",
            resume = "Resume auto-scroll",
        )
    }
}
