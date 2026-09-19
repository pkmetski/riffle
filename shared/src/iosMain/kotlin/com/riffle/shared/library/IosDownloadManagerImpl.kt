package com.riffle.shared.library

import com.riffle.core.domain.ApplicationScope
import com.riffle.feature.library.DefaultDownloadManager
import com.riffle.feature.library.DownloadManager

/**
 * iOS host for the shared [DefaultDownloadManager]. Runs work on ApplicationScope so downloads
 * survive navigation away from the screen that started them.
 *
 * Public (not `internal`) on purpose: the XCTest unit-test target constructs this exact class to
 * drive the iOS download pipeline the app itself uses. Its behaviour used to be a hand-written
 * port that had drifted from Android's — notably `startWithoutProgress` did not dedupe a second
 * call while a silent run was still in flight.
 */
class IosDownloadManagerImpl(
    applicationScope: ApplicationScope,
) : DownloadManager by DefaultDownloadManager(applicationScope.coroutineScope)
