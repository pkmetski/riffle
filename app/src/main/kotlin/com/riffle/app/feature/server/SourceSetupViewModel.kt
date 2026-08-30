package com.riffle.app.feature.server

import androidx.lifecycle.ViewModel
import com.riffle.core.domain.PendingSource

/**
 * Scoped to the `server_setup` nested navigation graph. Holds the
 * authenticated-but-not-yet-persisted PendingSource so AddSourceScreen and
 * SelectLibrariesScreen can share it without routing the auth token through
 * nav arguments.
 */
class SourceSetupViewModel constructor() : ViewModel() {
    var pendingServer: PendingSource? = null
}
