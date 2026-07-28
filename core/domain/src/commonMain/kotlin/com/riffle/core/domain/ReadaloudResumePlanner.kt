package com.riffle.core.domain

import kotlin.math.abs

/** How narration should be positioned when the readaloud player is (re)opened and play begins. */
sealed interface ReadaloudStartPlan {
    /**
     * First play of the session (the player was never closed): start from the reader's current
     * position, as the spec requires. Resolves to chapter granularity because the reader locator
     * carries no sentence fragment.
     */
    object FromReaderPosition : ReadaloudStartPlan

    /** Reopened in place: continue from the sentence narrating when the player was closed. */
    data class Resume(val fragmentRef: String) : ReadaloudStartPlan

    /**
     * Reopened on a different page: start from the top of the current page. The chapter href is the
     * page's resource; the first visible sentence is resolved against the WebView by the caller.
     */
    data class PageTop(val href: String) : ReadaloudStartPlan
}

/**
 * Decides where narration starts when the readaloud player is reopened, from the position remembered
 * at close versus the reader's position now:
 *
 *  - Never closed (first play) → [ReadaloudStartPlan.FromReaderPosition].
 *  - Reopened on the same page → [ReadaloudStartPlan.Resume] the stopped sentence.
 *  - Reopened on a different page → [ReadaloudStartPlan.PageTop].
 *
 * "Same page" is chapter href + column (progression) in paginated mode. Scroll mode has no clean page
 * boundary, so it always resumes in place (the simplest behaviour for now).
 */
object ReadaloudResumePlanner {

    /**
     * Two reads of the same paginated column report the same progression; this guards float
     * round-trips through the locator JSON without colliding adjacent pages on any realistic chapter.
     */
    const val PAGE_PROGRESSION_EPSILON = 1e-4

    fun plan(
        isScroll: Boolean,
        closeHref: String?,
        closeProgression: Double?,
        resumeFragmentRef: String?,
        currentHref: String?,
        currentProgression: Double?,
    ): ReadaloudStartPlan {
        // null close href == the player was never closed this session: first play.
        if (closeHref == null) return ReadaloudStartPlan.FromReaderPosition
        if (resumeFragmentRef != null &&
            resumesInPlace(isScroll, closeHref, closeProgression, currentHref, currentProgression)
        ) {
            return ReadaloudStartPlan.Resume(resumeFragmentRef)
        }
        return ReadaloudStartPlan.PageTop(currentHref ?: closeHref)
    }

    private fun resumesInPlace(
        isScroll: Boolean,
        closeHref: String,
        closeProgression: Double?,
        currentHref: String?,
        currentProgression: Double?,
    ): Boolean {
        if (isScroll) return true
        if (currentHref == null || closeHref != currentHref) return false
        return abs((closeProgression ?: 0.0) - (currentProgression ?: 0.0)) < PAGE_PROGRESSION_EPSILON
    }
}
