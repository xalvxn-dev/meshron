package com.xalvxnlab.meshron.contacts

import com.xalvxnlab.meshron.crypto.Ed25519
import com.xalvxnlab.meshron.crypto.ZBase32
import com.xalvxnlab.meshron.crypto.hexToBytes
import com.xalvxnlab.meshron.crypto.toHex
import java.net.URLDecoder

data class Contact(
    /** Ed25519 public key, lowercase hex. This is also the peer's Hyperswarm key. */
    val keyHex: String,
    val name: String,
    val status: Status,
    val addedAt: Long,
    /** Their findable @username, as they announced it (may be empty). */
    val handle: String = "",
    /** You compared safety codes with them (in person or on a call). */
    val verified: Boolean = false,
) {
    enum class Status {
        /** You added them, or accepted their request. The app keeps trying to connect. */
        ADDED,

        /** They connected to you, but you haven't accepted yet. */
        REQUEST,

        /** Blocked: never dialled, their messages and calls are ignored, no new requests. */
        BLOCKED,
    }

    val id: String get() = ZBase32.encode(keyHex.hexToBytes())
    val shortId: String get() = id.take(6) + "…" + id.takeLast(4)
    val displayName: String get() = name.ifBlank { shortId }
}

/** Result of reading a pasted ID or share link. */
sealed interface ParsedContact {
    data class Ok(val keyHex: String, val name: String) : ParsedContact
    data class Invalid(val reason: String) : ParsedContact
}

object ContactParser {
    private val LINK = Regex("""meshron://id/([a-z0-9]+)(?:\?name=(\S*))?""")
    private val BARE_ID = Regex("""^[ybndrfg8ejkmcpqxot1uwisza345h769]{52}$""")

    /** Accepts a raw 52-char ID, a meshron://id/… link, or a message containing the link. */
    fun parse(input: String): ParsedContact {
        val text = input.trim()
        if (text.isEmpty()) return ParsedContact.Invalid("Paste an ID or link")
        val link = LINK.find(text)
        val (id, name) = when {
            link != null -> link.groupValues[1] to decodeName(link.groupValues[2])
            BARE_ID.matches(text) -> text to ""
            else -> return ParsedContact.Invalid("That doesn't look like a Meshron ID")
        }
        if (id.length != 52) return ParsedContact.Invalid("ID must be 52 characters")
        val key = runCatching { ZBase32.decode(id) }.getOrNull()
            ?: return ParsedContact.Invalid("ID has invalid characters")
        if (key.size != Ed25519.PUBLIC_KEY_SIZE) return ParsedContact.Invalid("ID has the wrong length")
        // Reject IDs that don't round-trip (stray bits in the last character).
        if (ZBase32.encode(key) != id) return ParsedContact.Invalid("ID is not valid")
        return ParsedContact.Ok(key.toHex(), name.take(64))
    }

    private fun decodeName(raw: String): String =
        if (raw.isEmpty()) "" else runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault("").trim()
}
