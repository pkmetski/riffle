package com.riffle.app.sync

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.sources.webdav.TestConnectionResult
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory
import com.riffle.feature.source.ui.WebdavConnectionTester
import com.riffle.feature.source.ui.WebdavTestOutcome

/**
 * Android's [WebdavConnectionTester]: probes a WebDAV annotation-sync target through
 * [WebDavAnnotationSyncTargetFactory].
 *
 * The factory lives in `core/sources/src/jvmMain`, so it is invisible to the android+ios
 * `:feature:source-ui` module that owns the Add-Source form — hence the seam. This adapter is the
 * only place that translates [TestConnectionResult] into the shared [WebdavTestOutcome]; keeping
 * it in a file of its own limits the ADR 0049 `Server*`-identifier allowlist entry
 * (`TestConnectionResult.ServerError` is a pre-existing HTTP result, not the Source/Service
 * taxonomy) to these few lines instead of the whole Koin module.
 */
class WebDavTargetConnectionTester(
    private val factory: WebDavAnnotationSyncTargetFactory,
) : WebdavConnectionTester {

    override suspend fun test(config: AnnotationSyncConfig): WebdavTestOutcome {
        val target = factory.create(config) ?: return WebdavTestOutcome.UnparseableUrl
        return when (val result = target.testConnection()) {
            TestConnectionResult.Success -> WebdavTestOutcome.Success
            TestConnectionResult.AuthFailed -> WebdavTestOutcome.AuthFailed
            is TestConnectionResult.InvalidUrl -> WebdavTestOutcome.InvalidUrl(result.message)
            is TestConnectionResult.NetworkError -> WebdavTestOutcome.NetworkError(result.message)
            is TestConnectionResult.TlsError -> WebdavTestOutcome.TlsError(result.message)
            is TestConnectionResult.ServerError -> WebdavTestOutcome.HttpError(result.code)
        }
    }
}
