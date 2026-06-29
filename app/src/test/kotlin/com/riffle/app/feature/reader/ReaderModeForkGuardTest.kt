package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for issue #320. Counts mode-discriminating references in
 * [EpubReaderScreen.kt] and fails if the count grows above the post-refactor baseline.
 *
 * Every new `isContinuous` / `orientation ==` site is a hint that a behaviour belongs behind the
 * [com.riffle.app.feature.reader.presenter.ReaderPresenter] seam instead of being forked in the
 * screen. The remaining sites are intentional UI-lifecycle decisions (Compose `remember` keys,
 * fragment recreation, cover-overlay state) — see the `// MODE-FORK:` comments at each call site
 * for the per-fork justification.
 *
 * When you legitimately need to add a new fork: update [MAX_MODE_BRANCHES] and add a
 * `// MODE-FORK:` justification comment at the new call site.
 */
class ReaderModeForkGuardTest {

    @Test
    fun `EpubReaderScreen does not accumulate new mode-discriminating branches`() {
        val source = File("src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt")
        assertTrue(
            "EpubReaderScreen.kt not found at expected path " +
                "(run JVM tests from the :app module's working directory)",
            source.exists(),
        )
        val pattern = Regex("""isContinuous|orientation\s*==""")
        val matches = source.readLines().withIndex()
            .filter { (_, line) -> pattern.containsMatchIn(line) }
            .map { (idx, line) -> "L${idx + 1}: ${line.trim()}" }

        assertTrue(
            "EpubReaderScreen.kt has ${matches.size} mode-discriminating references " +
                "(was at most $MAX_MODE_BRANCHES after #320). New forks should usually go behind " +
                "ReaderPresenter; if this one is genuinely a UI-lifecycle fork, mark it with a " +
                "// MODE-FORK: comment and bump the baseline.\n" +
                matches.joinToString("\n"),
            matches.size <= MAX_MODE_BRANCHES,
        )
    }

    companion object {
        /**
         * Post-refactor baseline (#320). Lower this when a future refactor moves more branches
         * behind the seam; raise it only when a new fork is genuinely UI-lifecycle-shaped and
         * carries a `// MODE-FORK:` justification.
         */
        private const val MAX_MODE_BRANCHES = 28
    }
}
