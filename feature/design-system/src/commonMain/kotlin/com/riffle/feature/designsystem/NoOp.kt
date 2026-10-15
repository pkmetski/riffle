package com.riffle.feature.designsystem

import com.riffle.feature.library.FacetType

object NoOp {
    val unit: () -> Unit = {}

    val string: (String) -> Unit = {}

    /** Playlist advancement is not applicable when there is no playlist context. */
    val playlistAdvanceUnavailable: (String, String) -> Unit = { _, _ -> }

    /** Facet navigation is not available in the Downloads context — there is no library tab to drill into. */
    val facetNavUnavailableInDownloads: (String, FacetType, String) -> Unit = { _, _, _ -> }

    /** Series navigation is not available in the Downloads context — there is no library tab to navigate to. */
    val seriesNavUnavailableInDownloads: (String, String, String) -> Unit = { _, _, _ -> }

    /** "Show all annotations" is not applicable when using the iOS back-stack model. */
    val showAllAnnotationsUnavailableOnIos: (String) -> Unit = {}

    /** The back-stack in this iOS context is managed by the host, not by the screen. */
    val backHandledByIosHost: () -> Unit = {}
}
