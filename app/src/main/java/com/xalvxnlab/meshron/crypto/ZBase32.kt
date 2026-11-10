package com.xalvxnlab.meshron.crypto

/**
 * z-base-32 encoding (same alphabet as the `z32` npm package used by Holepunch tools),
 * so IDs shown here match what Hyperswarm/Keet-style tools print for a 32-byte key.
 */
object ZBase32 {
    private const val ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /** Throws [IllegalArgumentException] on characters outside the alphabet. */
    fun decode(text: String): ByteArray {
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var i = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Invalid z-base-32 character: $c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[i++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out
    }
}
