package com.riffle.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ConnectivityObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityObserverImpl @Inject constructor(
    @ApplicationContext context: Context,
    applicationScope: ApplicationScope,
) : ConnectivityObserver {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = applicationScope.coroutineScope

    override val isOnline: StateFlow<Boolean> = callbackFlow {
        // Online-state is derived from callback events rather than re-queried on each transition.
        // Re-querying ConnectivityManager.activeNetwork inside onLost is racy: at the instant the
        // callback fires, the just-lost network can still appear active and validated, which would
        // emit `true` and — because our NetworkRequest filters on VALIDATED — never produce another
        // event for that network. Result: airplane mode never flipped the offline banner.
        val tracker = ValidatedNetworkTracker<Network>()
        trySend(currentOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(tracker.onAvailable(network))
            }

            override fun onLost(network: Network) {
                trySend(tracker.onLost(network))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(tracker.onCapabilitiesChanged(network, hasInternet))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentOnline())

    private fun currentOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isMetered(): Boolean = connectivityManager.isActiveNetworkMetered
}
