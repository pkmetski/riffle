package com.riffle.core.data.localfiles

import com.riffle.core.catalog.CatalogFileStream
import com.riffle.core.catalog.abs.CatalogException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.File
import java.io.FileInputStream

internal class AndroidLocalFilesFileReader : LocalFilesFileReader {

    override fun toFileUrl(path: String): String = File(path).toURI().toString()

    override suspend fun <T> withFileStream(
        path: String,
        sizeBytes: Long?,
        block: suspend (CatalogFileStream) -> T,
    ): T {
        val f = File(path)
        if (!f.exists()) {
            throw CatalogException.UnsupportedFormat(
                "LocalFiles copied path missing: $path",
            )
        }
        val length = f.length()
        val input = FileInputStream(f)
        return try {
            block(
                object : CatalogFileStream {
                    override val contentLength: Long = length
                    override val channel: ByteReadChannel = input.toByteReadChannel()
                },
            )
        } finally {
            input.close()
        }
    }
}
