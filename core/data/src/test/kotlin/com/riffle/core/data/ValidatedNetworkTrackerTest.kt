package com.riffle.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedNetworkTrackerTest {

    @Test
    fun `onLost of the only validated network flips online to false`() {
        // Regression for the airplane-mode bug: onLost previously re-queried
        // ConnectivityManager.activeNetwork, which can still report the just-lost network as
        // validated. Now online-state must be derived from the tracker's own bookkeeping.
        val tracker = ValidatedNetworkTracker<String>()
        assertTrue(tracker.onAvailable("wifi"))

        assertFalse(tracker.onLost("wifi"))
    }

    @Test
    fun `onLost keeps online true while another validated network remains`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")

        assertTrue(tracker.onLost("wifi"))
    }

    @Test
    fun `capabilities changed dropping validated removes the network`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")

        assertFalse(tracker.onCapabilitiesChanged("wifi", hasValidatedInternet = false))
    }

    @Test
    fun `capabilities changed regaining validated restores the network`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onCapabilitiesChanged("wifi", hasValidatedInternet = false)

        assertTrue(tracker.onCapabilitiesChanged("wifi", hasValidatedInternet = true))
    }

    @Test
    fun `duplicate onAvailable for the same network does not double-count`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onAvailable("wifi")

        assertFalse(tracker.onLost("wifi"))
    }

    @Test
    fun `onLost for an unknown network is a no-op`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")

        assertTrue(tracker.onLost("cellular"))
    }

    @Test
    fun `mergeIn adds fresh networks without removing existing ones`() {
        // Regression for the resume-from-sleep bug: NetworkCallback events can be coalesced during
        // Android 13 doze so onAvailable is missed after wake. The reconciliation sweep adds any
        // still-validated networks discovered from ConnectivityManager without dropping networks
        // callbacks have already reported.
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("cellular")

        assertTrue(tracker.mergeIn(setOf("wifi")))
        // Both networks now tracked — either being lost keeps us online.
        assertTrue(tracker.onLost("cellular"))
        assertFalse(tracker.onLost("wifi"))
    }

    @Test
    fun `mergeIn with an empty snapshot preserves the existing set`() {
        // A stale getAllNetworks() during airplane-mode teardown must NOT clobber a correct
        // onLost-derived offline state — mergeIn only unions in fresh networks, never removes.
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")

        assertTrue(tracker.mergeIn(emptySet()))
    }

    @Test
    fun `clear drops every tracked network`() {
        // Called from the ProcessLifecycleOwner ON_START sweep when
        // `ConnectivityManager.activeNetwork` is null — the coarse ground-truth signal that no
        // radio is currently routable (airplane mode, all radios off, etc.).
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")

        assertFalse(tracker.clear())
    }
}
