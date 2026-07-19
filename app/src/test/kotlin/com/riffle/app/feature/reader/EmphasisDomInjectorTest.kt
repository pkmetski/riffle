package com.riffle.app.feature.reader

import com.riffle.core.models.EmphasisStyle
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards ADR 0046's bold/italic DOM injector against the "cross-line-break" regression:
 * a selection spanning two paragraphs (or a `<br>`) arrives with a newline where the
 * concatenated `document.body` text nodes have nothing at all, so the naive `indexOf`
 * approach found no match and silently failed to wrap the range. The fix has two moving
 * parts inside the injected script — this test pins both so a revert flips it red.
 */
class EmphasisDomInjectorTest {

    private val bold = EmphasisDomInjector.EmphasisRange(
        id = "a1",
        textSnippet = "foo\nbar",
        textBefore = "",
        styles = setOf(EmphasisStyle.BOLD),
    )

    @Test
    fun `script splices a synthetic space at block-level boundaries between text nodes`() {
        val script = EmphasisDomInjector.script(listOf(bold))
        // The synthetic-boundary insertion is what makes `<p>foo</p><p>bar</p>` searchable
        // — reverting to plain textContent concatenation removes this hook.
        assertTrue(
            "script must call isBoundaryBetween on consecutive text nodes",
            script.contains("isBoundaryBetween(lastNode, n)"),
        )
        assertTrue(
            "boundary check must inspect block ancestors",
            script.contains("blockAncestor(a) !== blockAncestor(b)"),
        )
        assertTrue(
            "boundary check must catch a <br> sitting between text nodes",
            script.contains("hasBrBetween"),
        )
        assertTrue(
            "synthetic pieces must be tagged so range-mapping can snap around them",
            script.contains("synthetic: true"),
        )
    }

    @Test
    fun `script matches snippet with whitespace-tolerant regex`() {
        val script = EmphasisDomInjector.script(listOf(bold))
        // `\s+` in the constructed regex is what lets a Readium "\n" snippet match a
        // synthetic space (or vice versa). Removing this collapse re-introduces the bug.
        assertTrue(
            "script must build a regex with a whitespace-tolerant escape helper",
            script.contains("toWsTolerantRegex"),
        )
        assertTrue(
            "whitespace runs must collapse to \\s+ in the regex",
            script.contains("""replace(/\s+/g, '\\s+')"""),
        )
    }
}
