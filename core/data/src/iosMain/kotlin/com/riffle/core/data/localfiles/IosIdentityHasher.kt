package com.riffle.core.data.localfiles

private const val PREFIX_BYTES = 64 * 1024

internal object IosIdentityHasher {

    fun hash(prefix: ByteArray, sizeBytes: Long): String {
        val sizeLabel = sizeBytes.toString().encodeToByteArray()
        val input = if (prefix.size <= PREFIX_BYTES) {
            prefix + sizeLabel
        } else {
            prefix.copyOf(PREFIX_BYTES) + sizeLabel
        }
        return sha1Hex(input)
    }

    private fun sha1Hex(data: ByteArray): String =
        sha1(data).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun sha1(data: ByteArray): ByteArray {
        val h = intArrayOf(
            0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt(),
        )

        val msgLenBits = data.size.toLong() * 8
        val padded = mutableListOf<Byte>().also { it.addAll(data.asList()) }
        padded.add(0x80.toByte())
        while ((padded.size + 8) % 64 != 0) padded.add(0)
        for (i in 7 downTo 0) padded.add(((msgLenBits ushr (i * 8)) and 0xFF).toByte())

        val msg = padded.toByteArray()

        for (chunkStart in 0 until msg.size step 64) {
            val w = IntArray(80)
            for (i in 0 until 16) {
                w[i] = ((msg[chunkStart + i * 4].toInt() and 0xFF) shl 24) or
                    ((msg[chunkStart + i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((msg[chunkStart + i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (msg[chunkStart + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            }

            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
            for (i in 0 until 80) {
                val (f, k) = when {
                    i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                    i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
        }

        val result = ByteArray(20)
        for (i in 0 until 5) {
            result[i * 4] = (h[i] ushr 24).toByte()
            result[i * 4 + 1] = (h[i] ushr 16).toByte()
            result[i * 4 + 2] = (h[i] ushr 8).toByte()
            result[i * 4 + 3] = h[i].toByte()
        }
        return result
    }
}
