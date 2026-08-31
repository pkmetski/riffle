package com.riffle.core.data

import com.riffle.core.common.FileStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
class IosFileStore : FileStore {
    private val documentsDir: String by lazy {
        @Suppress("UNCHECKED_CAST")
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true) as List<String>)
            .firstOrNull() ?: error("Cannot resolve iOS documents directory")
    }

    override fun resolve(namespace: String, relativePath: String): String {
        val base = "$documentsDir/$namespace"
        NSFileManager.defaultManager.createDirectoryAtPath(
            base,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return if (relativePath.isEmpty()) base else "$base/$relativePath"
    }
}
