package com.xalvxnlab.meshron.account

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password crypto for public accounts (same as server/accounts/test/flow.js):
 *   salt    = SHA-256("meshron-account-v1:" + username)[0..16]
 *   master  = Argon2id(password, salt, 3 passes, 64 MiB, 1 lane) -> 64 bytes
 *   authKey = master[0..32]   proves the password to the server (it keeps only a hash)
 *   lockKey = master[32..64]  locks the identity seed; never leaves the phone
 *   blob    = base64(nonce12 || ChaCha20-Poly1305(lockKey, seed, ad = "meshron-blob-v1"))
 */
object AccountCrypto {
    class Keys(val authKey: ByteArray, val lockKey: ByteArray) {
        val authHex get() = authKey.joinToString("") { "%02x".format(it) }
        fun wipe() { authKey.fill(0); lockKey.fill(0) }
    }

    fun derive(username: String, password: String): Keys {
        val salt = MessageDigest.getInstance("SHA-256").digest(("meshron-account-v1:" + username).toByteArray()).copyOf(16)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3).withMemoryAsKB(64 * 1024).withParallelism(1).withSalt(salt).build()
        val out = ByteArray(64)
        val pw = password.toByteArray(Charsets.UTF_8)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, out)
        pw.fill(0)
        return Keys(out.copyOfRange(0, 32), out.copyOfRange(32, 64)).also { out.fill(0) }
    }

    private val AD = "meshron-blob-v1".toByteArray()

    fun lock(lockKey: ByteArray, seed: ByteArray, nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }): String {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(lockKey, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(AD)
        return java.util.Base64.getEncoder().encodeToString(nonce + c.doFinal(seed))
    }

    fun unlock(lockKey: ByteArray, blob: String): ByteArray {
        val b = java.util.Base64.getDecoder().decode(blob)
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(lockKey, "ChaCha20"), IvParameterSpec(b.copyOfRange(0, 12)))
        c.updateAAD(AD)
        return c.doFinal(b, 12, b.size - 12)
    }

    /** How strong a password is, 0..4, with a hint. */
    fun strength(pw: String): Pair<Int, String> {
        if (pw.length < 8) return 0 to "At least 8 characters"
        var s = 1
        if (pw.length >= 12) s++
        if (pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() }) s++
        if (pw.any { it.isDigit() } && pw.any { !it.isLetterOrDigit() }) s++
        val common = listOf("password", "12345678", "qwerty", "iloveyou", "meshron")
        if (common.any { pw.lowercase().contains(it) }) s = 1
        return s.coerceAtMost(4) to listOf("Too weak", "Weak", "Okay", "Good", "Strong")[s.coerceAtMost(4)]
    }
}
