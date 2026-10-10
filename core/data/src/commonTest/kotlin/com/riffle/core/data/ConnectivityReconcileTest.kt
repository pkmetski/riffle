package com.riffle.core.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive truth-table tests for [reconcileOnline] — the predicate that gates every
 * `ConnectivityObserver.isOnline` emit. This file exists specifically to fence off the
 * "banner-doesn't-appear-when-airplane-mode-is-on" regression, which has shipped multiple times
 * under subtly different root causes (issues #294 and PR #392, and again during the Android 13
 * doze/dropped-onLost investigation). Every regression narrows to one of the four corners of
 * this table — if the predicate keeps passing these, the observer stays honest.
 *
 * The scenario tests below the truth-table document the concrete real-world failure each corner
 * corresponds to. Do NOT collapse them into a parameterised runner — the failure message on a
 * broken build should read like a mini-changelog of the regression class.
 */
class ConnectivityReconcileTest {

    @Test
    fun `tracker online and active network present emits online`() {
        // Healthy state — every subsystem agrees.
        assertTrue(reconcileOnline(trackerOnline = true, hasActiveNetwork = true))
    }

    @Test
    fun `tracker offline and active network absent emits offline`() {
        // Clean disconnect — both signals agree we're offline.
        assertFalse(reconcileOnline(trackerOnline = false, hasActiveNetwork = false))
    }

    @Test
    fun `tracker offline but active network still present emits offline`() {
        // Airplane-mode teardown race: the just-lost network can briefly linger in
        // activeNetwork after onLost has already fired. The tracker is authoritative for the
        // teardown direction.
        assertFalse(reconcileOnline(trackerOnline = false, hasActiveNetwork = true))
    }

    @Test
    fun `tracker online but active network absent emits offline`() {
        // On Android 13, when airplane mode toggles multiple validated networks off in quick
        // succession, NetworkCallback can silently drop or coalesce one of the onLost events.
        // The null activeNetwork veto makes the offline banner appear.
        assertFalse(reconcileOnline(trackerOnline = true, hasActiveNetwork = false))
    }

    @Test
    fun `dropped onLost scenario end-to-end via tracker`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")
        val trackerAfterDroppedOnLost = tracker.onLost("wifi")

        assertTrue(trackerAfterDroppedOnLost, "Tracker alone still thinks we're online")
        assertFalse(
            reconcileOnline(trackerAfterDroppedOnLost, hasActiveNetwork = false),
            "Reconciliation with null activeNetwork must veto to offline",
        )
    }

    @Test
    fun `foreground poll rescues stuck-online tracker via null activeNetwork`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        val trackerStillOnline = tracker.isOnline()

        assertTrue(trackerStillOnline, "Tracker never received the dropped onLost")
        assertFalse(
            reconcileOnline(trackerStillOnline, hasActiveNetwork = false),
            "The poll's fresh activeNetwork read must veto to offline",
        )
    }

    @Test
    fun `foreground poll rescues stuck-online tracker via currentOnline returning false`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        assertTrue(tracker.isOnline(), "Tracker still thinks online before clear")

        val afterClear = tracker.clear()

        assertFalse(afterClear, "Tracker must be empty after clear")
        assertFalse(
            reconcileOnline(afterClear, hasActiveNetwork = true),
            "reconcileOnline with cleared tracker must be offline",
        )
    }

    @Test
    fun `VPN network does not qualify and is excluded from tracker`() {
        assertFalse(
            isQualifyingNetwork(hasInternet = true, hasValidated = true, isVpn = true),
            "VPN network must not qualify even with INTERNET capability",
        )
    }

    @Test
    fun `physical network qualifies when not VPN`() {
        assertTrue(
            isQualifyingNetwork(hasInternet = true, hasValidated = false, isVpn = false),
            "Physical network with INTERNET must qualify",
        )
    }

    @Test
    fun `VPN airplane mode scenario end-to-end via tracker`() {
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        assertTrue(tracker.isOnline(), "Online with wifi in tracker")

        val trackerAfterWifiLost = tracker.onLost("wifi")

        assertFalse(trackerAfterWifiLost, "Tracker must be empty after wifi lost")
        assertFalse(
            reconcileOnline(trackerAfterWifiLost, hasActiveNetwork = true),
            "Offline must be emitted even though VPN activeNetwork is still non-null",
        )
    }

    @Test
    fun `dropped onAvailable scenario end-to-end via tracker`() {
        val emptyTracker = ValidatedNetworkTracker<String>()
        val trackerOnline = emptyTracker.mergeIn(emptySet())

        assertFalse(trackerOnline)
        assertFalse(reconcileOnline(trackerOnline, hasActiveNetwork = true))
    }
}
