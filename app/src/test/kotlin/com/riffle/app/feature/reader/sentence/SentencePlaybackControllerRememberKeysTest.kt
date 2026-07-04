package com.riffle.app.feature.reader.sentence

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for the "highlight stuck in first-composition mode" bug (fix 9ef052d).
 *
 * [SentencePlaybackController] is constructed inside `EpubReaderScreen.EpubNavigatorView`
 * via `remember(…) { SentencePlaybackController(highlightRenderer = { highlightRenderer }, …) }`.
 * The passed lambdas capture the enclosing composable's `val` bindings AT CLOSURE-CREATION
 * TIME — Kotlin captures the values, not a live reference into the caller's local variable
 * slot. If the `remember { … }` block has no keys, a mode flip
 * (Paginated/Vertical ↔ Continuous) rebinds the local `highlightRenderer` to a fresh
 * renderer instance but the cached controller keeps invoking the pre-flip renderer.
 * Result: whichever mode was current at first composition keeps getting the sentence
 * highlight; the other modes silently drop it — a classic "worked before, broke after
 * a refactor" regression that took two rounds to pin down.
 *
 * The check is on the source file rather than a runtime Compose harness because
 * `compose-ui-test` isn't on this module's `test` source set. A source-string check is
 * intentionally simple: it catches an accidental removal of the keys, which is exactly
 * the regression this test guards against. It does not try to prove that Compose
 * re-invokes the block correctly at runtime — that's the compiler's job.
 *
 * If you rename any of the three keyed locals, update the pattern. If you ADD a new
 * per-mode dependency to the controller (a fourth renderer/presenter), it belongs in
 * the key list too — extend the pattern to lock it in.
 */
class SentencePlaybackControllerRememberKeysTest {
    @Test
    fun `EpubReaderScreen remembers SentencePlaybackController keyed on all three renderer refs`() {
        // Anchor from the JVM test working dir (project root), matching how
        // PlayerCoordinatorScopeTest reads its source file.
        val source = File("src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt")
        assertTrue(
            "EpubReaderScreen.kt must exist at ${source.absolutePath}",
            source.exists(),
        )
        val text = source.readText()

        // Match the exact `remember(highlightRenderer, readerPresenter, readiumPresenter)` call
        // that wraps the SentencePlaybackController construction. Whitespace/order-tolerant
        // enough to survive trivial reformatting; strict enough to fail if a key is dropped
        // or a keyless `remember { … }` sneaks back in.
        val keyedRememberPattern = Regex(
            """remember\s*\(\s*highlightRenderer\s*,\s*readerPresenter\s*,\s*readiumPresenter\s*\)\s*\{\s*\n\s*SentencePlaybackController\s*\("""
        )
        assertTrue(
            "SentencePlaybackController construction in EpubReaderScreen must live inside " +
                "remember(highlightRenderer, readerPresenter, readiumPresenter) { … }. Without " +
                "these keys, the controller's captured lambdas hold the FIRST-composition " +
                "renderer/presenter and a mode flip (Paginated/Vertical ↔ Continuous) leaves " +
                "the sentence highlight painting on the pre-flip surface — the ADR-0039 " +
                "capture-time regression fixed in 9ef052d. If a keyless remember { … } has " +
                "been reintroduced, the bug is back.",
            keyedRememberPattern.containsMatchIn(text),
        )

        // Belt-and-braces: make sure there is no keyless `remember { SentencePlaybackController(`
        // anywhere in the file. Guards against the case where someone adds a second controller
        // instance without keys, or refactors the fix out.
        val keylessRememberPattern = Regex(
            """remember\s*\{\s*\n?\s*SentencePlaybackController\s*\("""
        )
        assertTrue(
            "A keyless `remember { SentencePlaybackController(…) }` reintroduces the ADR-0039 " +
                "capture-time bug: the constructor's lambdas freeze on first composition and " +
                "later mode flips can't route highlights to the current renderer. Add the " +
                "renderer/presenter keys (see the primary assertion above).",
            !keylessRememberPattern.containsMatchIn(text),
        )
    }
}
