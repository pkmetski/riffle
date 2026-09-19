package com.riffle.app.feature.library

import com.riffle.feature.library.DefaultDownloadManager
import kotlinx.coroutines.CoroutineScope

/**
 * Android host for the shared [DefaultDownloadManager].
 *
 * The behaviour (app-scoped work that survives navigation, duplicate-tap idempotence, terminal
 * state on throw, silent promotion runs) lives in `feature:library/commonMain` so the iOS app
 * runs the identical implementation instead of a hand-maintained port. This class exists only so
 * Android's Koin graph and its unit tests keep their `DownloadManager(scope)` entry point.
 */
class DownloadManager(
    scope: CoroutineScope,
) : com.riffle.feature.library.DownloadManager by DefaultDownloadManager(scope)
