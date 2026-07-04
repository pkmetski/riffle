package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for issue #320. Counts mode-discriminating references in active code lines of
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
 *
 * Counting rules: single-line `//` comments are stripped before matching so that wording drift
 * inside an existing comment doesn't move the baseline. `/* … */` blocks aren't stripped — a
 * known coarse-grained edge that hasn't bitten in practice.
 */
class ReaderModeForkGuardTest {

    @Test
    fun `EpubReaderScreen does not accumulate new mode-discriminating branches`() {
        val source = resolveScreenSource()
        val pattern = Regex("""isContinuous|orientation\s*==""")
        val matches = source.readLines().withIndex()
            .map { (idx, line) -> idx to stripLineComment(line) }
            .filter { (_, codeOnly) -> pattern.containsMatchIn(codeOnly) }
            .map { (idx, codeOnly) -> "L${idx + 1}: ${codeOnly.trim()}" }

        assertTrue(
            "EpubReaderScreen.kt has ${matches.size} mode-discriminating references " +
                "(was at most $MAX_MODE_BRANCHES after #320). New forks should usually go behind " +
                "ReaderPresenter; if this one is genuinely a UI-lifecycle fork, mark it with a " +
                "// MODE-FORK: comment and bump the baseline.\n" +
                matches.joinToString("\n"),
            matches.size <= MAX_MODE_BRANCHES,
        )
    }

    /**
     * Best-effort source-file lookup. Gradle's `:app:testDebugUnitTest` runs with the module dir
     * as cwd, but IntelliJ's default test runner uses the repo root — try both before giving up.
     */
    private fun resolveScreenSource(): File {
        val rel = "src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt"
        val candidates = listOf(File(rel), File("app/$rel"))
        return candidates.firstOrNull { it.exists() } ?: error(
            "EpubReaderScreen.kt not found. Tried: ${candidates.map { it.absolutePath }}",
        )
    }

    /** Drop everything from the first unquoted `//` to end-of-line. Crude but adequate. */
    private fun stripLineComment(line: String): String {
        var i = 0
        var inString = false
        while (i < line.length - 1) {
            val c = line[i]
            if (c == '"' && (i == 0 || line[i - 1] != '\\')) inString = !inString
            else if (!inString && c == '/' && line[i + 1] == '/') return line.substring(0, i)
            i++
        }
        return line
    }

    companion object {
        /**
         * Post-refactor baseline (#320). Lower this when a future refactor moves more branches
         * behind the seam; raise it only when a new fork is genuinely UI-lifecycle-shaped and
         * carries a `// MODE-FORK:` justification.
         */
        // Raised from 26 to 28 for the continuous-only annotation-nav mark-lookup path (see the
        // `// MODE-FORK:` comment above the annotationNavigationEvents LaunchedEffect). Two refs:
        // the LaunchedEffect key on isContinuous and the branch guard inside the collector — both
        // load-bearing and both untypeable behind ReaderPresenter without leaking mode internals.
        private const val MAX_MODE_BRANCHES = 29
    }
}
