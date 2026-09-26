package com.riffle.core.common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.posix.uint8_tVar
import platform.zlib.MAX_MEM_LEVEL
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(bytes: ByteArray): ByteArray = memScoped {
    val length = CC_SHA256_DIGEST_LENGTH.toInt()
    val digest = allocArray<uint8_tVar>(length)
    if (bytes.isEmpty()) {
        CC_SHA256(null, 0u, digest)
    } else {
        bytes.usePinned { pinned -> CC_SHA256(pinned.addressOf(0), bytes.size.toUInt(), digest) }
    }
    ByteArray(length) { digest[it].toByte() }
}

// windowBits 15 + 16 selects gzip framing for deflate; 15 + 32 lets inflate auto-detect gzip/zlib.
private const val GZIP_WINDOW_BITS = 15 + 16
private const val AUTO_DETECT_WINDOW_BITS = 15 + 32
private const val CHUNK = 64 * 1024

@OptIn(ExperimentalForeignApi::class)
actual fun gzip(bytes: ByteArray): ByteArray {
    val zs = nativeHeap.alloc<z_stream>()
    val init = deflateInit2_(
        zs.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, GZIP_WINDOW_BITS, MAX_MEM_LEVEL, Z_DEFAULT_STRATEGY,
        ZLIB_VERSION, kotlinx.cinterop.sizeOf<z_stream>().toInt(),
    )
    check(init == Z_OK) { "deflateInit2 failed: $init" }
    try {
        val out = ArrayList<Byte>(bytes.size / 2 + 64)
        val buffer = ByteArray(CHUNK)
        bytes.usePinned { srcPin ->
            buffer.usePinned { outPin ->
                zs.next_in = if (bytes.isEmpty()) null else srcPin.addressOf(0).reinterpret()
                zs.avail_in = bytes.size.toUInt()
                do {
                    zs.next_out = outPin.addressOf(0).reinterpret()
                    zs.avail_out = CHUNK.toUInt()
                    val rc = deflate(zs.ptr, Z_FINISH)
                    check(rc == Z_OK || rc == Z_STREAM_END || rc == Z_BUF_ERROR) { "deflate failed: $rc" }
                    val produced = CHUNK - zs.avail_out.toInt()
                    for (i in 0 until produced) out.add(buffer[i])
                } while (rc != Z_STREAM_END)
            }
        }
        return out.toByteArray()
    } finally {
        deflateEnd(zs.ptr)
        nativeHeap.free(zs)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun gunzip(bytes: ByteArray): ByteArray {
    val zs = nativeHeap.alloc<z_stream>()
    val init = inflateInit2_(zs.ptr, AUTO_DETECT_WINDOW_BITS, ZLIB_VERSION, kotlinx.cinterop.sizeOf<z_stream>().toInt())
    check(init == Z_OK) { "inflateInit2 failed: $init" }
    try {
        val out = ArrayList<Byte>(bytes.size * 3 + 64)
        val buffer = ByteArray(CHUNK)
        bytes.usePinned { srcPin ->
            buffer.usePinned { outPin ->
                zs.next_in = if (bytes.isEmpty()) null else srcPin.addressOf(0).reinterpret()
                zs.avail_in = bytes.size.toUInt()
                var rc: Int
                do {
                    zs.next_out = outPin.addressOf(0).reinterpret()
                    zs.avail_out = CHUNK.toUInt()
                    rc = inflate(zs.ptr, Z_NO_FLUSH)
                    check(rc == Z_OK || rc == Z_STREAM_END) { "inflate failed: $rc" }
                    val produced = CHUNK - zs.avail_out.toInt()
                    for (i in 0 until produced) out.add(buffer[i])
                    // Z_OK with nothing consumed and nothing produced would spin forever on a truncated stream.
                    check(rc == Z_STREAM_END || produced > 0 || zs.avail_in > 0u) { "inflate: truncated gzip stream" }
                } while (rc != Z_STREAM_END)
            }
        }
        return out.toByteArray()
    } finally {
        inflateEnd(zs.ptr)
        nativeHeap.free(zs)
    }
}
