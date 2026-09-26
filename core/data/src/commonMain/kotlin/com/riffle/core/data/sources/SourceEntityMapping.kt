package com.riffle.core.data.sources

import com.riffle.core.database.SourceEntity
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl

/**
 * The one `sources` row → [Source] projection both platforms use. Previously duplicated as an
 * `internal` copy in Android's `CredentialedSourceInstaller` and a `private` copy in
 * `IosSourceRepositoryImpl` — the two had not drifted yet, but nothing would have failed if they
 * had (#1101).
 */
fun SourceEntity.toDomain(): Source {
    val parsedUrl = SourceUrl.parse(url) ?: SourceUrl.parse("https://invalid.example.com")!!
    return Source(
        id = id,
        url = parsedUrl,
        isActive = isActive,
        insecureConnectionAllowed = insecureConnectionAllowed,
        username = username,
        type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.ABS),
        serverType = ServerType.fromStorageString(serverType),
        absUserId = absUserId,
    )
}
