package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadaloudResumePlannerTest {

    private fun plan(
        isScroll: Boolean = false,
        closeHref: String? = "text/c5.html",
        closeProgression: Double? = 0.40,
        resumeFragmentRef: String? = "text/c5.html#c5-s12",
        currentHref: String? = "text/c5.html",
        currentProgression: Double? = 0.40,
    ) = ReadaloudResumePlanner.plan(
        isScroll, closeHref, closeProgression, resumeFragmentRef, currentHref, currentProgression,
    )

    @Test
    fun `first play of the session starts from the reader position`() {
        // closeHref null == never closed.
        assertEquals(ReadaloudStartPlan.FromReaderPosition, plan(closeHref = null, closeProgression = null))
    }

    @Test
    fun `reopened on the same page resumes the stopped sentence`() {
        assertEquals(
            ReadaloudStartPlan.Resume("text/c5.html#c5-s12"),
            plan(closeProgression = 0.40, currentProgression = 0.40),
        )
    }

    @Test
    fun `tiny float drift on the same column still resumes`() {
        assertEquals(
            ReadaloudStartPlan.Resume("text/c5.html#c5-s12"),
            plan(closeProgression = 0.40, currentProgression = 0.40 + 1e-6),
        )
    }

    @Test
    fun `reopened on a different page in the same chapter starts at the page top`() {
        assertEquals(
            ReadaloudStartPlan.PageTop("text/c5.html"),
            plan(closeProgression = 0.40, currentProgression = 0.55),
        )
    }

    @Test
    fun `reopened in a different chapter starts at the page top of the new chapter`() {
        assertEquals(
            ReadaloudStartPlan.PageTop("text/c6.html"),
            plan(currentHref = "text/c6.html"),
        )
    }

    @Test
    fun `scroll mode always resumes in place regardless of position`() {
        assertEquals(
            ReadaloudStartPlan.Resume("text/c5.html#c5-s12"),
            plan(isScroll = true, currentHref = "text/c9.html", currentProgression = 0.99),
        )
    }

    @Test
    fun `no remembered sentence falls through to page top`() {
        assertEquals(
            ReadaloudStartPlan.PageTop("text/c5.html"),
            plan(resumeFragmentRef = null),
        )
    }
}
