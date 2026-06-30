package com.riffle.app.feature.reader.renderer

/**
 * The pieces of JS the paged/vertical EPUB reader injects into every freshly loaded page. Each
 * capability declares the others it depends on so [RendererBridge] can install them in a single
 * topo-sorted pass — the dependency graph is the contract, not the order of [evaluateJavascript]
 * calls scattered through call sites. Re-discovering (e.g.) that the footnote bridge needs the
 * rect-toJSON polyfill is therefore a compile-time fact, not an integration-test surprise.
 */
internal enum class CapabilityId {
    /** ClientRect.toJSON polyfill — required by Readium's reflowable tap hit-test on API ≤ 27. */
    RectToJsonPolyfill,

    /** Selection-change tracker that stashes the current span id + rect on `window`. */
    SelectionSpanTracker,

    /** Targeted CSS overrides injected as a `<style>` (fonts, margins). Idempotent by element id. */
    TypographyOverride,

    /** Click interceptor for in-document anchors — works around Readium 3.0.0's dotted-id footnote bug. */
    FootnoteAnchorBridge,

    /** Reserves a bottom strip for the readaloud mini-player (paginated mode only). */
    ReadaloudReserve,

    /** At-rest column-snap backstop — rounds an off-grid resting page to the nearest column. */
    ScrollSettleBackstop,
}

/** Where in the renderer's lifetime a capability is meant to be (re)installed. */
internal enum class CapabilityScope {
    /** Re-applied on every page load (each newly served resource is a fresh document). */
    PageLoad,
}

/**
 * One injectable JS capability. Capabilities are declared once at bridge construction; the bridge
 * installs them in dependency order on the appropriate lifecycle event (see [CapabilityScope]).
 *
 * All install scripts must be idempotent — they re-run on every onPageLoaded, including for the
 * same DOM during reflow ticks.
 */
internal data class RendererCapability(
    val id: CapabilityId,
    val dependsOn: Set<CapabilityId> = emptySet(),
    val scope: CapabilityScope = CapabilityScope.PageLoad,
    val installScript: () -> String,
)

/**
 * Topological sort of [capabilities] so each capability runs strictly after the ones it declares
 * a dependency on. Stable per the declared order. Throws on a missing dep or a cycle — both are
 * programming errors that should surface at construction (the bridge's init block runs this).
 */
internal fun topoSortCapabilities(capabilities: List<RendererCapability>): List<RendererCapability> {
    val byId = capabilities.associateBy { it.id }
    val sorted = ArrayList<RendererCapability>(capabilities.size)
    val visited = HashSet<CapabilityId>()
    val onStack = HashSet<CapabilityId>()

    fun visit(id: CapabilityId) {
        if (id in visited) return
        if (id in onStack) error("Renderer capability cycle involving $id")
        val cap = byId[id] ?: error("Renderer capability $id is missing a registration")
        onStack += id
        cap.dependsOn.forEach(::visit)
        onStack -= id
        visited += id
        sorted += cap
    }

    capabilities.forEach { visit(it.id) }
    return sorted
}
