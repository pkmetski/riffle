package com.riffle.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // Airplane-mode teardown race (#294): the just-lost network can briefly linger in
        // `ConnectivityManager.activeNetwork` after `onLost` has already fired. The tracker
        // (which reflects the callback we just received) is authoritative for the teardown
        // direction — we must NOT re-emit online just because the OS's live snapshot lags.
        assertFalse(reconcileOnline(trackerOnline = false, hasActiveNetwork = true))
    }

    @Test
    fun `tracker online but active network absent emits offline`() {
        // The "shipped-for-the-fourth-time" case: on Android 13, when airplane mode toggles
        // multiple validated networks off in quick succession, `NetworkCallback` can silently
        // drop or coalesce one of the `onLost` events. The tracker retains that network and
        // thinks we're still online — but `activeNetwork` is null because in reality no radio
        // is routable. The `activeNetwork == null` veto is what makes the banner appear.
        assertFalse(reconcileOnline(trackerOnline = true, hasActiveNetwork = false))
    }

    @Test
    fun `dropped onLost scenario end-to-end via tracker`() {
        // Full narrative reproduction of the Android 13 regression, wiring the tracker's
        // realistic post-drop state through the reconciliation predicate. The system had two
        // validated networks (wifi + cellular). Airplane mode is toggled ON. The OS delivers
        // `onLost` for wifi but drops the one for cellular — the tracker retains cellular and
        // reports online=true. Without the predicate this would keep the banner hidden
        // indefinitely; WITH the predicate the null `activeNetwork` vetoes and we emit offline.
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")
        val trackerAfterDroppedOnLost = tracker.onLost("wifi") // only one of two onLost delivered

        assertTrue("Tracker alone still thinks we're online", trackerAfterDroppedOnLost)
        assertFalse(
            "Reconciliation with null activeNetwork must veto to offline",
            reconcileOnline(trackerAfterDroppedOnLost, hasActiveNetwork = false),
        )
    }

    @Test
    fun `foreground poll rescues stuck-online tracker via null activeNetwork`() {
        // The dropped-onLost scenario: airplane mode ON while the library screen is in the
        // foreground. The OS drops the `onLost` for the single qualifying network, so the tracker
        // remains non-empty and thinks we're online. On AOSP Android 13+, `activeNetwork` becomes
        // null quickly after going offline, so the poll's `reconcileOnline(trackerStillOnline,
        // hasActiveNetwork = false)` correctly vetoes and emits offline.
        //
        // Do not delete if the poll is refactored — rewire to the new mechanism.
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        val trackerStillOnline = tracker.isOnline()

        assertTrue("Tracker never received the dropped onLost", trackerStillOnline)
        assertFalse(
            "The poll's fresh activeNetwork read (null after airplane on) must veto to offline",
            reconcileOnline(trackerStillOnline, hasActiveNetwork = false),
        )
    }

    @Test
    fun `foreground poll rescues stuck-online tracker via currentOnline returning false`() {
        // When the poll's `currentOnline()` scan finds NO qualifying physical network (all physical
        // networks are gone, or the only network is a VPN the tracker never recorded), `currentOnline()`
        // returns false and the poll calls `tracker.clear()`. This covers both:
        //   - AOSP Android 13+: OS nulls `activeNetwork` → currentOnline() = false via null check
        //   - Samsung / VPN split-tunnel: physical networks gone but VPN handle stays alive;
        //     currentOnline() iterates allNetworks and finds no qualifying physical network → false
        // Either way, poll clears the tracker → offline emitted despite `activeNetwork` being non-null.
        val tracker = ValidatedNetworkTracker<String>()
        tracker.onAvailable("wifi")
        assertTrue("Tracker still thinks online before clear", tracker.isOnline())

        // currentOnline() returned false → poll calls tracker.clear()
        val afterClear = tracker.clear()

        assertFalse("Tracker must be empty after clear", afterClear)
        assertFalse(
            "reconcileOnline with cleared tracker and any activeNetwork value must be offline",
            reconcileOnline(afterClear, hasActiveNetwork = true),
        )
    }

    @Test
    fun `VPN network does not qualify and is excluded from tracker`() {
        // Tailscale and other VPN tunnels (TRANSPORT_VPN) remain alive in airplane mode at the
        // kernel level — their tun interface, link addresses, and capabilities are unchanged
        // whether or not the VPN can relay traffic. They must never enter the tracker, so that
        // a physical-network onLost (which Samsung DOES fire) empties the tracker correctly.
        assertFalse(
            "VPN network must not qualify even with INTERNET capability",
            isQualifyingNetwork(hasInternet = true, hasValidated = true, isVpn = true),
        )
    }

    @Test
    fun `physical network qualifies when not VPN`() {
        assertTrue(
            "Physical network with INTERNET must qualify",
            isQualifyingNetwork(hasInternet = true, hasValidated = false, isVpn = false),
        )
    }

    @Test
    fun `VPN airplane mode scenario end-to-end via tracker`() {
        // Samsung + Tailscale regression reproduction:
        // 1. Device is online; both a physical network (WiFi) and Tailscale VPN are present.
        // 2. The VPN is EXCLUDED from the tracker (filtered in onAvailable by TRANSPORT_VPN check).
        // 3. User enables airplane mode: physical onLost fires (Samsung DOES deliver this), VPN onLost
        //    is dropped (Samsung doesn't fire it). Tracker removes only the physical network.
        // 4. Tracker is now empty → offline correctly detected, regardless of VPN still being alive.
        val tracker = ValidatedNetworkTracker<String>()

        // VPN is never added (filtered out in onAvailable)
        // Physical WiFi is added
        tracker.onAvailable("wifi")
        assertTrue("Online with wifi in tracker", tracker.isOnline())

        // Airplane mode: Samsung delivers onLost for wifi but drops it for the VPN (which wasn't
        // in the tracker anyway). Tracker removes wifi → empty.
        val trackerAfterWifiLost = tracker.onLost("wifi")

        assertFalse("Tracker must be empty after wifi lost", trackerAfterWifiLost)
        assertFalse(
            "Offline must be emitted even though VPN activeNetwork is still non-null",
            reconcileOnline(trackerAfterWifiLost, hasActiveNetwork = true),
        )
    }

    @Test
    fun `dropped onAvailable scenario end-to-end via tracker`() {
        // Symmetric case — post-doze/wake on Android 13, the OS can drop an `onAvailable` after
        // the process resumes, so the tracker remains empty (offline) even though a validated
        // network exists. Callback tracker says offline, but reconciliation with an active
        // network alone must NOT flip us online — the `ProcessLifecycleOwner` ON_START sweep is
        // what heals this direction by `mergeIn`-ing the missed network into the tracker. This
        // test locks in that the predicate does not synthesise online purely from
        // `activeNetwork`; the tracker has to catch up first.
        val emptyTracker = ValidatedNetworkTracker<String>()
        val trackerOnline = emptyTracker.mergeIn(emptySet())

        assertFalse(trackerOnline)
        assertFalse(reconcileOnline(trackerOnline, hasActiveNetwork = true))
    }
}
