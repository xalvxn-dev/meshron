package com.xalvxnlab.meshron.account

import com.xalvxnlab.meshron.crypto.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Known answers produced by libsodium (server/accounts/test): the phone must match exactly. */
class AccountCryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun matchesLibsodium() {
        val k = AccountCrypto.derive("rae_test", "pässword 123")
        assertEquals(
            "e3bc8323a04fa6f7b99d5437e60efaba9be4d577e16c96e28a3077cb17fa9323b68b8ff474a371ba76c82cecb4ddd383c314a60bb2db4a729e96d38ecba0ef39",
            hex(k.authKey) + hex(k.lockKey),
        )
        val seed = ByteArray(32) { 7 }
        val blob = AccountCrypto.lock(k.lockKey, seed, ByteArray(12) { 9 })
        assertEquals("CQkJCQkJCQkJCQkJGl72CzJ3uqZKuQeB8x2Qp6i/Pqi8BiufBMDXKnS74X6kM9CW2LB9SyFkTVu0v4GU", blob)
        assertArrayEquals(seed, AccountCrypto.unlock(k.lockKey, blob))
        assertEquals(
            "9db4005f95efb43148cbe048de6c980da385251cc273ef53a18c9b163b160a6e16554f9437c1f8109deeeeb1c17406138c282e488568fc59c48664a590dfbd0a",
            hex(Ed25519.sign(seed, "meshron-signup-v1:rae_test".toByteArray())),
        )
    }
}
