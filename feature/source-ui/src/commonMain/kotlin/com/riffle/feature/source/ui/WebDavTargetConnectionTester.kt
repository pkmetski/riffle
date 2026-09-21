package com.riffle.feature.source.ui

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.sources.webdav.TestConnectionResult
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory

/**
 * Probes a WebDAV annotation-sync target through [WebDavAnnotationSyncTargetFactory] and
 * translates the low-level [TestConnectionResult] into the [WebdavTestOutcome] the shared
 * add-source screen renders.
 *
 * Both hosts bind this. It used to live in `:app` because `core:sources`' WebDAV client was
 * `jvmMain`-only, so iOS bound a lambda that returned [WebdavTestOutcome.UnparseableUrl] for
 * every input — the WebDAV backend of the shared add-source screen existed, was reachable, and
 * could not succeed. With the client in `commonMain` the real tester compiles for Kotlin/Native
 * and the stub is gone.
 *
 * This is the only place [TestConnectionResult] is mapped onto [WebdavTestOutcome]; a second copy
 * is how the two platforms would start reporting different failures for the same server.
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
