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
