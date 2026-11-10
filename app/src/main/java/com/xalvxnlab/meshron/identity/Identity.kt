package com.xalvxnlab.meshron.identity

import com.xalvxnlab.meshron.crypto.ZBase32
import java.net.URLEncoder

class Identity(
    val publicKey: ByteArray,
    val username: String,
    val createdAt: Long,
    /** Findable @username without the @ (may be empty). */
    val handle: String = "",
) {
    /** Public ID shown to users: z-base-32 of the Ed25519 public key (52 chars). */
    val id: String = ZBase32.encode(publicKey)

    val shortId: String get() = id.take(6) + "…" + id.takeLast(4)

    /** Link encoded in the QR code / share sheet. Contains only public data. */
    val shareLink: String
        get() = "meshron://id/$id?name=" + URLEncoder.encode(username, "UTF-8")

    companion object {
        const val MAX_NAME_LENGTH = 32

        fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

        fun isValidName(raw: String): Boolean = cleanName(raw).length in 1..MAX_NAME_LENGTH

        private val HANDLE = Regex("^[a-z0-9_]{3,20}$")

        /** "@Bob_1 " -> "bob_1". */
        fun cleanHandle(raw: String): String = raw.trim().removePrefix("@").lowercase()

        fun isValidHandle(raw: String): Boolean = HANDLE.matches(cleanHandle(raw))

        /** Why a handle can't be used, or null if it's fine. */
        fun handleProblem(raw: String): String? {
            val h = cleanHandle(raw)
            return when {
                h.length < 3 -> "At least 3 characters"
                h.length > 20 -> "At most 20 characters"
                !HANDLE.matches(h) -> "Only letters, numbers and _"
                else -> null
            }
        }
    }
}
