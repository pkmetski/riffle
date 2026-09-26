package com.riffle.core.data

import com.riffle.core.sources.webdav.AnnotationSyncException
import com.riffle.core.sync.CycleOutcome

/** Classify a thrown exception into a [CycleOutcome.Failed] subtype. */
fun Throwable.toFailedCycleOutcome(at: Long): CycleOutcome.Failed = when (this) {
    is AnnotationSyncException.AuthFailed -> CycleOutcome.Failed.Auth(at, code)
    is AnnotationSyncException.HttpFailure -> CycleOutcome.Failed.Server(at, code)
    is AnnotationSyncException.NetworkError -> CycleOutcome.Failed.Network(at, message)
    is AnnotationSyncException.TlsError -> CycleOutcome.Failed.Tls(at, message)
    else -> CycleOutcome.Failed.Unknown(at, message)
}
