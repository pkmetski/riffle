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
    fun `reset replaces the tracked set with the fresh snapshot`() {
        // Regression for the resume-from-sleep bug: NetworkCallback events can be coalesced during
        // Android 13 doze so onLost fires without a matching onAvailable on wake. syncNow() must
        // discard the accumulated event history and re-seed from a live ConnectivityManager sweep.
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("stale-wifi")

        assertTrue(tracker.reset(setOf("live-wifi")))
        assertTrue(tracker.onLost("stale-wifi"))
        assertFalse(tracker.onLost("live-wifi"))
    }

    @Test
    fun `reset to an empty snapshot flips online to false`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")

        assertFalse(tracker.reset(emptySet()))
    }
}
