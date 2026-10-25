package com.riffle.feature.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostWiringProbeTest {

    @Test
    fun noViolationsWhenNoFieldIsNoOp() {
        val fields = listOf(
            "onBack" to ({}),
            "onNext" to ({}),
        )
        assertTrue(HostWiringProbe.check(fields).isEmpty())
    }

    @Test
    fun detectsNoOpUnit() {
        val fields = listOf("onBack" to NoOp.unit)
        assertEquals(listOf("onBack"), HostWiringProbe.check(fields))
    }

    @Test
    fun detectsNoOpString() {
        val fields = listOf("onSearch" to NoOp.string)
        assertEquals(listOf("onSearch"), HostWiringProbe.check(fields))
    }

    @Test
    fun detectsPlaylistAdvanceUnavailable() {
        val fields = listOf("onPlaylistAdvance" to NoOp.playlistAdvanceUnavailable)
        assertEquals(listOf("onPlaylistAdvance"), HostWiringProbe.check(fields))
    }

    @Test
    fun detectsFacetNavUnavailableInDownloads() {
        val fields = listOf("onFacetSelected" to NoOp.facetNavUnavailableInDownloads)
        assertEquals(listOf("onFacetSelected"), HostWiringProbe.check(fields))
    }

    @Test
    fun detectsSeriesNavUnavailableInDownloads() {
        val fields = listOf("onNavigateToSeries" to NoOp.seriesNavUnavailableInDownloads)
        assertEquals(listOf("onNavigateToSeries"), HostWiringProbe.check(fields))
    }

    @Test
    fun detectsBackHandledByIosHost() {
        val fields = listOf("onNavigateBack" to NoOp.backHandledByIosHost)
        assertEquals(listOf("onNavigateBack"), HostWiringProbe.check(fields))
    }

    @Test
    fun allowlistedNoOpsAreNotReported() {
        val fields = listOf(
            "onNavigateBack" to NoOp.backHandledByIosHost,
        )
        val allowlist = setOf("onNavigateBack")
        assertTrue(HostWiringProbe.check(fields, allowlist).isEmpty())
    }

    @Test
    fun onlyUnallowlistedViolationsAreReported() {
        val fields = listOf(
            "onNavigateBack" to NoOp.backHandledByIosHost,
            "onFacetSelected" to NoOp.facetNavUnavailableInDownloads,
        )
        val allowlist = setOf("onNavigateBack")
        assertEquals(listOf("onFacetSelected"), HostWiringProbe.check(fields, allowlist))
    }

    @Test
    fun freshLambdasAreNotFlaggedAsNoOps() {
        val fields = listOf(
            "onBack" to ({} as () -> Unit),
            "onSearch" to ({ _: String -> } as (String) -> Unit),
        )
        assertTrue(HostWiringProbe.check(fields).isEmpty())
    }
}
