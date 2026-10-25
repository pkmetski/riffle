package com.riffle.feature.designsystem

/**
 * Runtime check that no callback field in a host-wiring bundle is accidentally assigned a known
 * [NoOp] constant without being listed in the [allowlist].
 *
 * Each action bundle exposes a `probeFields()` extension that maps field names to their values.
 * The probe then checks each value against the [NoOp] singletons via reference equality.
 *
 * Usage:
 *   val violations = HostWiringProbe.check(actions.probeFields(), allowlist = setOf("onNavigateBack"))
 *   check(violations.isEmpty()) { "Unwired no-op callbacks: $violations" }
 */
object HostWiringProbe {

    private val knownNoOps: Set<Any> = buildSet {
        add(NoOp.unit)
        add(NoOp.string)
        add(NoOp.playlistAdvanceUnavailable)
        add(NoOp.facetNavUnavailableInDownloads)
        add(NoOp.seriesNavUnavailableInDownloads)
        add(NoOp.backHandledByIosHost)
    }

    /**
     * Returns the names of fields whose values are a known [NoOp] constant but are not
     * in [allowlist]. An empty result means every field is wired to a real implementation
     * (or is a deliberate, documented no-op in the allowlist).
     */
    fun check(
        fields: List<Pair<String, Any>>,
        allowlist: Set<String> = emptySet(),
    ): List<String> = fields
        .filter { (name, value) -> value in knownNoOps && name !in allowlist }
        .map { it.first }
}
