package com.riffle.core.network.model

import kotlinx.serialization.Serializable

// Shape returned by ABS's unauthenticated `/status` endpoint. The version field is
// `serverVersion` — there is no `/api/server-info` endpoint (a misnomer in earlier specs).
@Serializable
internal data class AbsServerInfoResponse(val serverVersion: String)
