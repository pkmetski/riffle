package com.riffle.app.feature.reader

import kotlin.math.roundToInt

// Reserves a bottom strip in the paginated reader while the readaloud mini-player is open, so the
// player (and the chapter rail beneath it) no longer float over live text — the bug where the
// player covered the last lines, worst in landscape.
//
// HOW: the reserve is injected as extra `padding-bottom` on `:root` — the same lever the page-margin
// override uses (see TypographyOverride.kt's "margins" entry). Padding on the multicol container
// shrinks every column's height, so Readium re-paginates *live* with the last lines lifted above the
// player. This is the only thing that reflows post-creation: a native WebView resize (Compose padding
// or View.setPadding) is ignored once the page is laid out, but a CSS change reflows immediately,
// exactly like the formatting panel's margin slider (submitPreferences).
//
// PAGINATED ONLY: scroll/vertical mode needs no reserve — text there isn't pinned to a page, so
// anything under the player is one small scroll away. Scoping to paginated also avoids re-enabling
// the inset path that caused the scroll-mode cutout band (shouldApplyInsetsPadding = false).

/** Stable element id for the injected `<style>` so repeated injections don't accumulate. */
private const val RESERVE_STYLE_ID = "riffle-readaloud-reserve"

/** Class toggled on `<html>` to activate the reserve rule, and the CSS var carrying its height. */
private const val RESERVE_ACTIVE_CLASS = "riffle-ra-on"
private const val RESERVE_VAR = "--riffle-ra-reserve"

/**
 * CSS-px bottom reserve (≈ dp; WebView CSS-px == dp since both divide device-px by the same
 * density/devicePixelRatio) to hold the floating player+rail clear of the text. Zero — i.e. no
 * reserve — unless the player is open AND the reader is paginated. The visible *gap* above the
 * player is added by the CSS itself (the page's own bottom margin), so this is just the height of
 * the floating stack that must be cleared.
 */
internal fun readaloudReserveCssPx(
    playerOpen: Boolean,
    paginated: Boolean,
    overlayHeightDp: Float,
): Int {
    if (!playerOpen || !paginated) return 0
    return overlayHeightDp.coerceAtLeast(0f).roundToInt()
}

/**
 * The reserve rule. Active only while `<html>` carries [RESERVE_ACTIVE_CLASS]. The bottom padding is
 * the page's own bottom margin (same calc as the margins override, so the gap above the player tracks
 * the user's margin setting) PLUS the floating-stack height carried in [RESERVE_VAR]. The doubled
 * `:root` raises specificity to (0,3,0) so it beats both Readium's `:root{padding:0}` (0,1,0) and the
 * margins override (0,2,0) regardless of source order. `--USER__pageMargins` defaults to 1 so the
 * `calc()` stays valid on books left at the default margin (an unset var with no fallback would void
 * the whole declaration and drop the reserve).
 */
internal fun readaloudReserveCss(): String =
    """
    :root.$RESERVE_ACTIVE_CLASS:root {
      padding-bottom: calc(
        var(--RS__pageGutter, 8px) * var(--USER__pageMargins, 1) + var($RESERVE_VAR, 0px)
      ) !important;
    }
    """.trimIndent()

/**
 * Idempotently injects [readaloudReserveCss] as a `<style>` in `document.head`. Re-run on every page
 * load (each resource is a fresh document); the stable id prevents duplicates.
 */
internal fun readaloudReserveInjectionJs(): String {
    val css = readaloudReserveCss()
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\$", "\\\$")
    return """
        (function() {
          var id = '$RESERVE_STYLE_ID';
          if (document.getElementById(id)) return;
          var style = document.createElement('style');
          style.id = id;
          style.textContent = `$css`;
          document.head.appendChild(style);
        })();
    """.trimIndent()
}

/**
 * Toggles the reserve on the live document: sets the height var and activates the rule when
 * [reserveCssPx] > 0, or clears both when it's 0. Setting/removing the var reflows the page
 * immediately — no fragment recreation. Re-run on page load (to re-apply to the new document) and
 * whenever the reserve changes (player opens/closes, rotation, rail visibility).
 */
internal fun readaloudReserveApplyJs(reserveCssPx: Int): String =
    if (reserveCssPx > 0) {
        """
        (function() {
          var d = document.documentElement;
          d.style.setProperty('$RESERVE_VAR', '${reserveCssPx}px');
          d.classList.add('$RESERVE_ACTIVE_CLASS');
        })();
        """.trimIndent()
    } else {
        """
        (function() {
          var d = document.documentElement;
          d.classList.remove('$RESERVE_ACTIVE_CLASS');
          d.style.removeProperty('$RESERVE_VAR');
        })();
        """.trimIndent()
    }
