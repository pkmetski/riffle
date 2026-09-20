package com.riffle.feature.reader.ui

import com.riffle.core.common.TimeRemaining
import kotlin.math.roundToInt

/**
 * The localised strings the chapter-map labels need, supplied by the host.
 *
 * The host owns the string catalogue: `:app` fills this from its `res/values*` `stringResource`s
 * (so Bulgarian and Spanish keep working exactly as before), and `:shared` fills it from
 * [ChapterMapProgressLabelTemplates.English] because the iOS app has no i18n mechanism wired up
 * yet. Only the *strings* differ per host — every derivation below is shared, so the two
 * platforms cannot drift on how a chapter count, a duration or a remaining-time readout is built.
 *
 * Each field is an Android-style positional template (`%1$d`, `%2$s`, `%%`). [formatTemplate]
 * expands them; it is deliberately not `String.format`, which is JVM-only.
 */
data class ChapterMapProgressLabelTemplates(
    /** `"Chapter %1$d of %2$d"` */
    val chapterCount: String,
    /** `"%1$d min"` */
    val durationMinutes: String,
    /** `"%1$dh %2$dmin"` */
    val durationHoursMinutes: String,
    /** `"%1$s chapter"` */
    val chapterRemainingExact: String,
    /** `"~%1$s chapter"` */
    val chapterRemainingEstimated: String,
    /** `"< 1min chapter"` */
    val chapterRemainingLessThanMinute: String,
    /** `"%1$s total"` */
    val bookRemainingExact: String,
    /** `"~%1$s total"` */
    val bookRemainingEstimated: String,
    /** `"< 1min total"` */
    val bookRemainingLessThanMinute: String,
    /** `"Reading progress: %1$s"` */
    val readingProgressValue: String,
    /** `"Current chapter: %1$s"` */
    val currentChapterValue: String,
    /** `"Total progress: %1$s"` */
    val totalProgressValue: String,
    /** `"Active rail segment: %1$s. Progress %2$d%%"` */
    val activeRailSegmentProgress: String,
) {
    companion object {
        /**
         * The English catalogue, verbatim from `app/src/main/res/values/strings.xml`. Used by the
         * iOS host, which has no string-resource mechanism yet (#1072's i18n item). Keep the two
         * in step: these are the same keys, not a second wording.
         */
        val English = ChapterMapProgressLabelTemplates(
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
    }
}

/**
 * Expand an Android-style positional template: `%N$s` / `%N$d` are replaced by `args[N - 1]`, and
 * `%%` yields a literal `%`. Anything else is copied through untouched.
 *
 * A shared substitute for `String.format(Locale, …)`, which exists only on the JVM. Positional
 * specifiers are all Riffle's reader strings use, and they are what translators need, so the
 * subset is deliberate rather than a partial reimplementation of printf.
 */
fun formatTemplate(template: String, vararg args: Any): String {
    val out = StringBuilder(template.length + 8)
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        if (i + 1 < template.length && template[i + 1] == '%') {
            out.append('%')
            i += 2
            continue
        }
        // %N$x — read the 1-based argument index, then the (ignored) conversion character.
        var j = i + 1
        var index = 0
        var digits = 0
        while (j < template.length && template[j].isDigit()) {
            index = index * 10 + (template[j] - '0')
            j++
            digits++
        }
        if (digits > 0 && j + 1 < template.length && template[j] == '$') {
            val arg = args.getOrNull(index - 1)
            if (arg != null) {
                out.append(arg.toString())
                i = j + 2
                continue
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** `"Chapter 3 of 12"`. The index is 0-based and clamped so it never reads "Chapter 13 of 12". */
fun formatChapterCount(
    activeChapterIndex: Int,
    chapterCount: Int,
    templates: ChapterMapProgressLabelTemplates,
): String = formatTemplate(
    templates.chapterCount,
    (activeChapterIndex + 1).coerceAtMost(chapterCount),
    chapterCount,
)

/** `"42 min"` under an hour, `"2h 05min"` above it. */
fun formatDuration(sec: Long, templates: ChapterMapProgressLabelTemplates): String {
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    return when {
        hours > 0 -> formatTemplate(templates.durationHoursMinutes, hours, minutes)
        else -> formatTemplate(templates.durationMinutes, minutes)
    }
}

/** `m:ss` under an hour, `h:mm:ss` above it. Shared substitute for `"%d:%02d".format(…)`. */
private fun clock(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

fun formatChapterRemaining(
    remaining: TimeRemaining,
    templates: ChapterMapProgressLabelTemplates,
): String = when (remaining) {
    is TimeRemaining.Exact -> formatTemplate(templates.chapterRemainingExact, clock(remaining.sec))
    is TimeRemaining.Estimated -> when {
        remaining.sec < 60 -> templates.chapterRemainingLessThanMinute
        else -> formatTemplate(templates.chapterRemainingEstimated, formatDuration(remaining.sec, templates))
    }
}

fun formatBookRemaining(
    remaining: TimeRemaining,
    templates: ChapterMapProgressLabelTemplates,
): String = when (remaining) {
    is TimeRemaining.Exact -> formatTemplate(templates.bookRemainingExact, clock(remaining.sec))
    is TimeRemaining.Estimated -> when {
        remaining.sec < 60 -> templates.bookRemainingLessThanMinute
        else -> formatTemplate(templates.bookRemainingEstimated, formatDuration(remaining.sec, templates))
    }
}

/**
 * `"37.4%"` — the whole-book percentage readout, one decimal.
 *
 * Shared substitute for `"%.1f%%".format(…)`. It *rounds* rather than truncating, because
 * `%.1f` rounds and the two must agree: 99.96% has to read "100.0%" on both platforms, not
 * "99.9%" on one of them.
 */
fun formatTotalProgressPercent(totalProgress: Float): String {
    val tenths = (totalProgress.coerceIn(0f, 1f) * 1000f).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}
