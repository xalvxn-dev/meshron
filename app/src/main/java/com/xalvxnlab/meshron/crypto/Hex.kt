package com.xalvxnlab.meshron.crypto

private const val DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Invalid hex" }
        ((hi shl 4) or lo).toByte()
    }
}
