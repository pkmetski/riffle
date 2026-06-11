package com.riffle.app.feature.reader

// Reserves a bottom strip in the paginated reader for the readaloud mini-player. Unlike the earlier
// (reverted) version, the reserve is NOT tied to the player being open — it is held for the whole
// session whenever readaloud is AVAILABLE (a downloaded/usable match) for the book. Because the
// reserve is constant, opening or closing the player never changes the layout, so the page can never
// jump when Play-from-here (or the play button) shows the player. That sidesteps the entire
// reflow-pin problem the open-gated reserve had: there is no reflow to pin across.
//
// HOW: the reserve is injected as extra `padding-bottom` on `:root` — the same lever the page-margin
// override uses (see TypographyOverride.kt's "margins" entry). Padding on the multicol container
// shrinks every column's height, so the text is paginated above the strip the player will occupy. A
// native WebView resize (Compose padding or View.setPadding) is ignored once the page is laid out,
// but a CSS change paginates correctly from the start, exactly like the formatting panel's margin
// slider.
//
// PAGINATED ONLY: scroll/vertical mode needs no reserve — text there isn't pinned to a page, so the
// player floats over the bottom and anything under it is one small scroll away. Scoping to paginated
// also keeps the inset path disabled (shouldApplyInsetsPadding = false), avoiding the scroll-mode
// cutout band.

/** Stable element id for the injected `<style>` so repeated injections don't accumulate. */
private const val RESERVE_STYLE_ID = "riffle-readaloud-reserve"

/** Class toggled on `<html>` to activate the reserve rule, and the CSS var carrying its height. */
private const val RESERVE_ACTIVE_CLASS = "riffle-ra-on"
private const val RESERVE_VAR = "--riffle-ra-reserve"

/**
 * Height (CSS px ≈ dp; a WebView CSS-px equals a dp because both divide device-px by the same
 * density/devicePixelRatio) of the strip held for the floating mini-player. Matches the player bar's
 * rendered height (a single row of 48dp icon buttons + 2×4dp row padding ≈ 56dp), so the player
 * exactly fills the reserve and the gap above it is the page's own bottom margin.
 */
internal const val READALOUD_RESERVE_DP = 56

/**
 * CSS-px bottom reserve to hold the floating player clear of the text — [READALOUD_RESERVE_DP] when
 * readaloud is available AND the reader is paginated, else 0 (no reserve; the page renders edge to
 * edge). Gated on availability, NOT on the player being open, so the value is stable across the
 * session and toggling the player never re-paginates.
 */
internal fun readaloudReserveDp(readaloudAvailable: Boolean, paginated: Boolean): Int =
    if (readaloudAvailable && paginated) READALOUD_RESERVE_DP else 0

/**
 * The reserve rule. Active only while `<html>` carries [RESERVE_ACTIVE_CLASS]. The bottom padding is
 * the page's own bottom margin — the SAME `--RS__pageGutter * --USER__pageMargins` calc the margins
 * override uses for its `padding-bottom` (the 1.0× bottom factor; the override's top is 0.5×, so this
 * is NOT symmetric with the top), so the gap above the player tracks the margins slider exactly —
 * PLUS the strip height carried in [RESERVE_VAR] (the player bar). The
 * doubled `:root` raises specificity to (0,3,0) so it beats both Readium's `:root{padding:0}` (0,1,0)
 * and the margins override (0,2,0) regardless of source order. `--USER__pageMargins` defaults to 1 so
 * the `calc()` stays valid on books left at the default margin (an unset var with no fallback would
 * void the whole declaration and drop the reserve).
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
 * [reserveCssPx] > 0, or clears both when it's 0. Setting/removing the var re-paginates the page
 * immediately — no fragment recreation. Re-run on page load (to re-apply to the new document) and
 * whenever the reserve changes (readaloud becomes available mid-session via a download, or the reader
 * switches to/from scroll mode).
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
