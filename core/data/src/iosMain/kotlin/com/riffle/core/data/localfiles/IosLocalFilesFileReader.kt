package com.riffle.core.data.localfiles

import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.abs.CatalogException
import io.ktor.utils.io.ByteReadChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal class IosLocalFilesFileReader : LocalFilesFileReader {

    override fun toFileUrl(path: String): String = "file://$path"

    override suspend fun <T> withFileStream(
        path: String,
        sizeBytes: Long?,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            throw CatalogException.UnsupportedFormat("LocalFiles copied path missing: $path")
        }
        val data = NSData.dataWithContentsOfFile(path)
            ?: throw CatalogException.UnsupportedFormat("Cannot read local file: $path")
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        val length = bytes.size.toLong()
        return block(
            object : CatalogFileStream {
                override val contentLength: Long = length
                override val channel: ByteReadChannel = ByteReadChannel(bytes)
            },
        )
    }
}
