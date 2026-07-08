package com.riffle.app.feature.reader.renderer

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * Recording fake [RendererBridge] for JVM unit tests — every call is appended to [calls] as a
 * human-readable string. Use to assert that a presenter / view-model drives the bridge in the
 * expected order without needing a live WebView.
 */
internal class FakeRendererBridge(
    override val capabilities: List<RendererCapability> = emptyList(),
    private val selectionSentenceResult: String? = null,
    private val readSelectionSpanResult: String? = null,
    private val followNarratedResult: String? = null,
    private val measureColumnsResult: List<Double> = emptyList(),
    private val firstVisibleSentenceResult: Int? = null,
    private val landedAtEndResult: Boolean = false,
    private val snapToElementMoved: Boolean = false,
    private val snapCadenceSpanResult: String? = null,
    private val scrollByPxResult: Boolean? = true,
    private val scrollBoundaryResult: Pair<Boolean, Boolean> = Pair(false, false),
    private val viewportFractionResult: Double? = null,
    private val cadenceFeatureDetectResult: String? = "false",
    private val cadenceTokeniseResult: String? = null,
    @Suppress("unused") private val cadenceStartSpanIdResult: String? = null,
) : RendererBridge {

    val calls: MutableList<String> = mutableListOf()

    override suspend fun installPageCapabilities(): List<CapabilityId> {
        calls += "installPageCapabilities"
        return capabilities.map { it.id }
    }

    override suspend fun applyTypographyOverride() {
        calls += "applyTypographyOverride"
    }

    override suspend fun applyReadaloudReserve(reservePx: Int) {
        calls += "applyReadaloudReserve($reservePx)"
    }

    override suspend fun applyFigureBorders(
        cssRules: List<String>,
        svgMatches: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.SvgMatch>,
    ) {
        calls += "applyFigureBorders(n=${cssRules.size},svg=${svgMatches.size})"
    }

    override suspend fun installScrollSettleBackstop() {
        calls += "installScrollSettleBackstop"
    }

    override suspend fun snapAfterGoTo(link: Link) {
        calls += "snapAfterGoTo(link=${link.href})"
    }

    override suspend fun snapAfterGoTo(locator: Locator, landAtStartWhenNoTarget: Boolean) {
        calls += "snapAfterGoTo(locator=${locator.href}, landAtStart=$landAtStartWhenNoTarget)"
    }

    override suspend fun snapToEnd() {
        calls += "snapToEnd"
    }

    override suspend fun snapToElement(fragmentId: String): Boolean {
        calls += "snapToElement($fragmentId)"
        return snapToElementMoved
    }

    override suspend fun snapCadenceSpan(fragmentId: String): String? {
        calls += "snapCadenceSpan($fragmentId)"
        return snapCadenceSpanResult
    }

    override suspend fun landedAtEnd(): Boolean {
        calls += "landedAtEnd"
        return landedAtEndResult
    }

    override suspend fun followNarratedSentence(text: String): String? {
        calls += "followNarratedSentence($text)"
        return followNarratedResult
    }

    override suspend fun measureNarratedColumns(text: String): List<Double> {
        calls += "measureNarratedColumns($text)"
        return measureColumnsResult
    }

    override suspend fun snapNarratedColumn(text: String, columnIndex: Int) {
        calls += "snapNarratedColumn($text, $columnIndex)"
    }

    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> {
        calls += "measureCadenceColumns($fragmentId)"
        return measureColumnsResult
    }

    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {
        calls += "snapCadenceColumn($fragmentId, $columnIndex)"
    }

    override suspend fun resolveSelectionSentence(sentences: List<Pair<String, String>>): String? {
        calls += "resolveSelectionSentence(n=${sentences.size})"
        return selectionSentenceResult
    }

    override suspend fun readSelectionSpanId(): String? {
        calls += "readSelectionSpanId"
        return readSelectionSpanResult
    }

    override suspend fun firstVisibleSentenceIndex(highlights: List<String>): Int? {
        calls += "firstVisibleSentenceIndex(n=${highlights.size})"
        return firstVisibleSentenceResult
    }

    override suspend fun scrollByPx(delta: Int): Boolean? {
        calls += "scrollByPx($delta)"
        return scrollByPxResult
    }

    override suspend fun scrollBoundary(): Pair<Boolean, Boolean> {
        calls += "scrollBoundary"
        return scrollBoundaryResult
    }

    override suspend fun evaluateBoundaryScroll(js: String) {
        calls += "evaluateBoundaryScroll(len=${js.length})"
    }

    override suspend fun readViewportFraction(): Double? {
        calls += "readViewportFraction"
        return viewportFractionResult
    }

    override suspend fun evaluateCadenceFeatureDetect(): String? {
        calls += "evaluateCadenceFeatureDetect"
        return cadenceFeatureDetectResult
    }

    override suspend fun evaluateCadenceTokenise(chapterHref: String, localeTag: String?): String? {
        calls += "evaluateCadenceTokenise($chapterHref, $localeTag)"
        return cadenceTokeniseResult
    }

    override suspend fun cadenceStartSpanId(): String? {
        calls += "cadenceStartSpanId"
        return cadenceStartSpanIdResult
    }

    val appliedHighlightPatches: MutableList<com.riffle.app.feature.reader.highlights.HighlightsDomPatch> =
        mutableListOf()

    override suspend fun applyHighlightDomPatch(
        patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch,
    ) {
        calls += "applyHighlightDomPatch"
        appliedHighlightPatches += patch
    }
}
