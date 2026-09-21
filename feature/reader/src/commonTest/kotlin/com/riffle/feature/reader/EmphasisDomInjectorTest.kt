package com.riffle.feature.reader

import com.riffle.core.models.EmphasisStyle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards ADR 0056's bold/italic DOM injector against the "cross-line-break" regression:
 * a selection spanning two paragraphs (or a `<br>`) arrives with a newline where the
 * concatenated `document.body` text nodes have nothing at all, so the naive `indexOf`
 * approach found no match and silently failed to wrap the range. The fix has two moving
 * parts inside the injected script — this test pins both so a revert flips it red.
 *
 * In `commonTest` since the injector moved to `commonMain`: iOS runs the identical script
 * through `ReadiumSwiftNavigator`'s JS seam, so the payload it builds has to be pinned on the
 * iOS compiler too — the hand-rolled JSON that replaced `org.json` is the part that could
 * plausibly differ.
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
            script.contains("isBoundaryBetween(lastNode, n)"),
            "script must call isBoundaryBetween on consecutive text nodes",
        )
        assertTrue(
            script.contains("blockAncestor(a) !== blockAncestor(b)"),
            "boundary check must inspect block ancestors",
        )
        assertTrue(
            script.contains("hasBrBetween"),
            "boundary check must catch a <br> sitting between text nodes",
        )
        assertTrue(
            script.contains("synthetic: true"),
            "synthetic pieces must be tagged so range-mapping can snap around them",
        )
    }

    @Test
    fun `script matches snippet with whitespace-tolerant regex`() {
        val script = EmphasisDomInjector.script(listOf(bold))
        // `\s+` in the constructed regex is what lets a Readium "\n" snippet match a
        // synthetic space (or vice versa). Removing this collapse re-introduces the bug.
        assertTrue(
            script.contains("toWsTolerantRegex"),
            "script must build a regex with a whitespace-tolerant escape helper",
        )
        assertTrue(
            script.contains("""replace(/\s+/g, '\\s+')"""),
            "whitespace runs must collapse to \\s+ in the regex",
        )
    }

    @Test
    fun payloadIsValidJsonWithEscapedControlCharacters() {
        // The org.json serialiser this replaced escaped these for us. A raw newline inside a
        // JSON string is a parse error, so the whole `var annotations = …` statement would
        // throw and no emphasis would ever be wrapped — on both platforms, silently.
        val script = EmphasisDomInjector.script(listOf(bold))
        assertTrue(script.contains("""{"id":"a1","snippet":"foo\nbar","before":"","styles":["bold"]}"""))
        assertFalse(
            script.substringAfter("var annotations = ").substringBefore(";").contains('\n'),
            "the payload literal must not contain a raw newline",
        )
    }

    @Test
    fun payloadEscapesAClosingScriptTagInsideASnippet() {
        // Spliced into JavaScript source: an unescaped `</script>` in a selected snippet ends
        // the script element early on Android and takes the rest of the injection with it.
        val hostile = EmphasisDomInjector.EmphasisRange(
            id = "x",
            textSnippet = "before </script> after",
            textBefore = "",
            styles = setOf(EmphasisStyle.ITALIC),
        )
        val script = EmphasisDomInjector.script(listOf(hostile))
        assertFalse(script.contains("</script>"), "a closing script tag must never survive verbatim")
        // Built by concatenation so the expectation itself cannot be mangled by a tool that
        // interprets `\u` sequences: "<" + "\/script" + ">".
        val escaped = "\\u003c" + "\\/script" + "\\u003e"
        assertTrue(script.contains(escaped), "expected the escaped form $escaped")
    }
}
