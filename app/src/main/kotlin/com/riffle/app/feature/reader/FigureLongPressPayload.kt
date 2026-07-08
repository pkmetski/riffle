package com.riffle.app.feature.reader

import org.json.JSONObject

/**
 * The richer figure payload posted by [FigureTapScript]'s long-press (500ms) listener — as opposed
 * to [FigureTapMessageParser]'s tap payload, this carries the resolved caption and (for `<svg>`
 * targets) the serialized markup, since long-press opens the annotate-figure flow rather than the
 * zoom overlay.
 *
 * [kind] is the lower-cased tag name (`"img"`, `"svg"`, `"picture"`). [href] is the resolved image
 * `src` for `img`/`picture` targets — null for `svg`. [svg] is the `outerHTML` for `svg` targets —
 * null otherwise. [elementId] is the target's `id` attribute, or null if unset.
 *
 * [rectX] / [rectY] / [rectW] / [rectH] are the long-pressed figure's `getBoundingClientRect()` in
 * CSS px, viewport-relative to the WebView that posted the payload — used to anchor
 * `HighlightActionsPopup` at the figure (Kotlin converts CSS px to device px via density, then to
 * screen coordinates). Default to `0` so a stale/old-format JS payload missing these fields doesn't
 * crash the parser; a zero rect just anchors the popup at the WebView's origin.
 */
internal data class FigureLongPressPayload(
    val kind: String,
    val caption: String,
    val href: String?,
    val svg: String?,
    val elementId: String?,
    val rectX: Int = 0,
    val rectY: Int = 0,
    val rectW: Int = 0,
    val rectH: Int = 0,
)

/**
 * Parses the JSON payload posted by [FigureTapScript]'s long-press listener into a
 * [FigureLongPressPayload]. `org.json.JSONObject#optString` collapses a JSON `null` to `""`, which
 * would corrupt the kind/href/svg-branch distinction downstream (Task 6 uses `href == null` /
 * `svg == null` to tell an image target from an inline-SVG target) — so every optional field is
 * explicitly re-nulled via `takeIf { !obj.isNull(...) }` after the `optString` read.
 */
internal object FigureLongPressMessageParser {
    fun parse(json: String): FigureLongPressPayload {
        val obj = JSONObject(json)
        return FigureLongPressPayload(
            kind = obj.getString("kind"),
            caption = obj.optString("caption", ""),
            href = obj.optString("href").takeIf { !obj.isNull("href") && it.isNotEmpty() },
            svg = obj.optString("svg").takeIf { !obj.isNull("svg") && it.isNotEmpty() },
            elementId = obj.optString("elementId").takeIf { !obj.isNull("elementId") && it.isNotEmpty() },
            rectX = obj.optInt("rectX", 0),
            rectY = obj.optInt("rectY", 0),
            rectW = obj.optInt("rectW", 0),
            rectH = obj.optInt("rectH", 0),
        )
    }
}
