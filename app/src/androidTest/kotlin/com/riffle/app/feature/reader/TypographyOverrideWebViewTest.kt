package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end verification that the typography-override CSS actually beats hostile publisher
 * styles in a real WebView. The fixture HTML mirrors the Safari Books Online pattern that
 * caused the original bug — every text rule is scoped under an ID selector
 * (`#sbo-rt-content p { line-height: 125% }`) which beats Readium's own body-level
 * user-property rules.
 *
 * These tests would have caught the bug before it shipped, and will catch any future
 * regression where the override stops winning the cascade (e.g. Readium renaming the
 * `--USER__*` variable channel, the gate selector being mistyped, or `!important` being
 * dropped from the override block).
 */
@RunWith(AndroidJUnit4::class)
class TypographyOverrideWebViewTest {

    // Hostile fixture: ID-scoped publisher rules with high specificity but no `!important`.
    // Mirrors the Safari Books Online template that the bug-report book uses.
    private val hostileSboFixture = """
        <!DOCTYPE html>
        <html>
          <head>
            <style>
              #sbo-rt-content p { line-height: 125%; text-align: left; font-family: serif; }
              p { font-size: 16px; }
            </style>
          </head>
          <body>
            <div id="sbo-rt-content">
              <p id="target">Quod erat demonstrandum.</p>
            </div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun overrideIsInertWhenUserVariableIsNotSetOnRoot() {
        withWebViewFixture(hostileSboFixture) { webView ->
            // No --USER__lineHeight on :root → gate doesn't match → override inert →
            // publisher's 125% must win.
            webView.evalSync(typographyOverrideInjectionJs())
            val lineHeightPx = webView.evalSync(
                "window.getComputedStyle(document.getElementById('target')).lineHeight"
            ).toCssPx()
            val fontSizePx = webView.evalSync(
                "window.getComputedStyle(document.getElementById('target')).fontSize"
            ).toCssPx()
            // 125% of 16px = 20px; assert ratio ~1.25 (publisher's value, NOT a user override).
            assertEquals(1.25, lineHeightPx / fontSizePx, 0.05)
        }
    }

    @Test
    fun overrideWinsWhenUserVariableIsSetOnRoot() {
        withWebViewFixture(hostileSboFixture) { webView ->
            // User customised line-spacing → Readium would write --USER__lineHeight on :root.
            // We simulate that here, then inject our override. Without the override the
            // publisher's #sbo-rt-content p { line-height: 125% } would still win because
            // specificity (1,0,1) beats anything Readium can do at `body` level with !important
            // (cascade per-element ignores body !important when <p> has its own rule).
            webView.evalSync("document.documentElement.style.setProperty('--USER__lineHeight', '1.8')")
            webView.evalSync(typographyOverrideInjectionJs())
            val lineHeightPx = webView.evalSync(
                "window.getComputedStyle(document.getElementById('target')).lineHeight"
            ).toCssPx()
            val fontSizePx = webView.evalSync(
                "window.getComputedStyle(document.getElementById('target')).fontSize"
            ).toCssPx()
            assertEquals(
                "Override should force line-height to user value (1.8 × fontSize), not the publisher's 125%",
                1.8, lineHeightPx / fontSizePx, 0.05,
            )
        }
    }

    @Test
    fun overrideWinsForJustifyText() {
        withWebViewFixture(hostileSboFixture) { webView ->
            webView.evalSync("document.documentElement.style.setProperty('--USER__textAlign', 'justify')")
            webView.evalSync(typographyOverrideInjectionJs())
            val textAlign = webView.evalSync(
                "window.getComputedStyle(document.getElementById('target')).textAlign"
            ).trim('"')
            assertEquals("justify", textAlign)
        }
    }

    @Test
    fun overrideIsInertForTextAlignWhenUserVariableIsNotSet() {
        withWebViewFixture(hostileSboFixture) { webView ->
            webView.evalSync(typographyOverrideInjectionJs())
            val textAlign = webView.evalSync(
                "window.getComputedStyle(document.getElementById('target')).textAlign"
            ).trim('"')
            // Publisher said `text-align: left` and no user var set → publisher should win.
            assertEquals("left", textAlign)
        }
    }

    // There is deliberately NO :root vertical-padding margin override. It used to inject
    // padding-top/bottom on :root in onPageLoaded — after first paint — so the page visibly dropped
    // by the margin amount on every chapter start (the "page falls from above" bug). The top/bottom
    // reading margin is now reserved before paint as native fragment-container padding (see
    // EpubReaderScreen), so the injected CSS must never pad :root, even with --USER__pageMargins set.
    @Test
    fun injectedCssNeverPadsRootEvenWhenUserMarginsAreSet() {
        withWebViewFixture(hostileSboFixture) { webView ->
            webView.evalSync("document.documentElement.style.setProperty('--RS__pageGutter', '20px')")
            webView.evalSync("document.documentElement.style.setProperty('--USER__pageMargins', '2')")
            webView.evalSync(typographyOverrideInjectionJs())
            val paddingTop = webView.evalSync(
                "window.getComputedStyle(document.documentElement).paddingTop"
            ).toCssPx()
            val paddingBottom = webView.evalSync(
                "window.getComputedStyle(document.documentElement).paddingBottom"
            ).toCssPx()
            assertEquals("injected CSS must not pad :root top (would reflow post-paint)", 0.0, paddingTop, 0.5)
            assertEquals("injected CSS must not pad :root bottom (would reflow post-paint)", 0.0, paddingBottom, 0.5)
        }
    }

    @Test
    fun repeatedInjectionDoesNotAccumulateStyleElements() {
        withWebViewFixture(hostileSboFixture) { webView ->
            repeat(5) { webView.evalSync(typographyOverrideInjectionJs()) }
            val count = webView.evalSync(
                "document.querySelectorAll('#riffle-typography-override').length.toString()"
            ).trim('"').toInt()
            assertEquals("Idempotent injection should only ever leave one <style> in the head", 1, count)
        }
    }

    // ---- helpers ----

    // `getComputedStyle().lineHeight` returns either a px string or "normal" — we never want
    // "normal" in these tests (the fixture sets explicit values), so parse the px number.
    private fun String.toCssPx(): Double {
        val unquoted = trim('"')
        require(unquoted.endsWith("px")) { "Expected pixel value, got: $unquoted" }
        return unquoted.removeSuffix("px").toDouble()
    }
}
