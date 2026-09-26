package com.riffle.core.common

/** SHA-256 digest of [bytes] (32 bytes). JVM: `MessageDigest`; iOS: CommonCrypto. */
expect fun sha256(bytes: ByteArray): ByteArray

/** GZIP-compress [bytes] (RFC 1952 framing, the format `java.util.zip.GZIPOutputStream` emits). */
expect fun gzip(bytes: ByteArray): ByteArray

/** Inflate a GZIP stream produced by [gzip] (or any RFC 1952 writer). Throws on malformed input. */
expect fun gunzip(bytes: ByteArray): ByteArray
