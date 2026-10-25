package com.riffle.feature.reader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
data class FigureLongPressPayload(
    val kind: String,
    val caption: String,
    val href: String?,
    val svg: String?,
    val elementId: String?,
    val rectX: Int = 0,
    val rectY: Int = 0,
    val rectW: Int = 0,
    val rectH: Int = 0,
    /**
     * Data URI (`data:image/…;base64,…`) of the figure, rasterised from the WebView at long-press
     * time via a canvas. Persisted on the annotation so both the annotations panel row's thumbnail
     * and the Highlights-mode elided reader can display the figure without needing the source
     * Publication container to be reloaded. Null on cross-origin blocks or SVG capture failure.
     */
    val imageBytes: String? = null,
    /**
     * Non-null when the caption resolves to a real DOM element — i.e. a range we can turn into a
     * proper text-highlight anchor. Null when the caption came from `alt`/`aria-label` or when no
     * caption resolved at all. When present the figure is persisted as TYPE_HIGHLIGHT covering the
     * caption text with the figure as an embeddedFigure; when null it falls back to TYPE_IMAGE.
     */
    val captionRange: CaptionRange? = null,
)

/**
 * A resolved caption's DOM text, plus a short text-context window on either side, captured on the
 * JS side at long-press time. Kotlin re-locates the caption in the chapter HTML via the same
 * locateSnippetInBody/buildHighlightCfiRange machinery a normal text selection uses.
 */
data class CaptionRange(
    val text: String,
    val textBefore: String,
    val textAfter: String,
)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parses the JSON payload posted by [FigureTapScript]'s long-press listener into a
 * [FigureLongPressPayload]. Explicit null-guarding mirrors the org.json version: a JSON `null`
 * field must produce a Kotlin null, not an empty string.
 */
object FigureLongPressMessageParser {
    fun parse(rawJson: String): FigureLongPressPayload {
        val obj: JsonObject = json.parseToJsonElement(rawJson).jsonObject
        fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun int(key: String): Int = obj[key]?.jsonPrimitive?.intOrNull ?: 0
        val captionRange = obj["captionRange"]?.jsonObject?.let { cr ->
            val text = cr["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (text.isNotBlank()) {
                CaptionRange(
                    text = text,
                    textBefore = cr["textBefore"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    textAfter = cr["textAfter"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            } else null
        }
        return FigureLongPressPayload(
            kind = obj["kind"]!!.jsonPrimitive.content,
            caption = str("caption").orEmpty(),
            href = str("href"),
            svg = str("svg"),
            elementId = str("elementId"),
            rectX = int("rectX"),
            rectY = int("rectY"),
            rectW = int("rectW"),
            rectH = int("rectH"),
            imageBytes = str("imageBytes"),
            captionRange = captionRange,
        )
    }
}
