package com.riffle.core.data

/**
 * Bookkeeping for the set of currently-validated networks observed via a
 * `ConnectivityManager.NetworkCallback`. Each event returns the up-to-date online state
 * (`isNotEmpty()`), so the callback emits a value derived from the events it has already received
 * rather than re-querying the system — which can lie at the exact instant a network is lost.
 */
internal class ValidatedNetworkTracker<K : Any> {
    private val validated = mutableSetOf<K>()

    fun onAvailable(network: K): Boolean {
        validated += network
        return validated.isNotEmpty()
    }

    fun onLost(network: K): Boolean {
        validated -= network
        return validated.isNotEmpty()
    }

    fun onCapabilitiesChanged(network: K, hasValidatedInternet: Boolean): Boolean {
        if (hasValidatedInternet) validated += network else validated -= network
        return validated.isNotEmpty()
    }

    /**
     * Union [fresh] into the tracked set. Callback events are authoritative for removal — a
     * reconciliation sweep only heals the "we missed an onAvailable during doze" direction and
     * must never re-add a network the callbacks have already reported as lost, or an airplane-mode
     * offline emit could be silently reverted by a stale `getAllNetworks()` snapshot.
     */
    fun mergeIn(fresh: Set<K>): Boolean {
        validated += fresh
        return validated.isNotEmpty()
    }

    /** Drop every tracked network — used when the reconciliation sweep sees a null
     * `activeNetwork`, i.e. no radio is currently routable. */
    fun clear(): Boolean {
        validated.clear()
        return false
    }
}
