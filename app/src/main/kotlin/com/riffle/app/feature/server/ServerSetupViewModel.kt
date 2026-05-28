package com.riffle.app.feature.server

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.PendingServer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Scoped to the `server_setup` nested navigation graph. Holds the
 * authenticated-but-not-yet-persisted PendingServer so AddServerScreen and
 * SelectLibrariesScreen can share it without routing the auth token through
 * nav arguments.
 */
@HiltViewModel
class ServerSetupViewModel @Inject constructor() : ViewModel() {
    var pendingServer: PendingServer? = null
}
