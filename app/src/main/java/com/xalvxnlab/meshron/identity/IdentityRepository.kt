package com.xalvxnlab.meshron.identity

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xalvxnlab.meshron.crypto.Ed25519
import com.xalvxnlab.meshron.crypto.KeystoreCipher
import com.xalvxnlab.meshron.crypto.ZBase32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.SecureRandom

private val Context.identityStore by preferencesDataStore(name = "identity")

/**
 * Owns the user's cryptographic identity. The private seed is only ever stored
 * encrypted by [KeystoreCipher]; plaintext copies are zeroed after use.
 */
class IdentityRepository(context: Context) {
    private val store = context.applicationContext.identityStore
    private val cipher = KeystoreCipher()

    val identity: Flow<Identity?> = store.data.map { p ->
        val pub = p[PUBLIC_KEY] ?: return@map null
        Identity(
            publicKey = Base64.decode(pub, Base64.NO_WRAP),
            username = p[USERNAME].orEmpty(),
            createdAt = p[CREATED_AT] ?: 0L,
            handle = p[HANDLE].orEmpty(),
        )
    }

    suspend fun create(username: String): Identity =
        createFromSeed(username, ByteArray(Ed25519.SEED_SIZE).also { SecureRandom().nextBytes(it) })

    /** Brings back an identity from its recovery key (see [recoveryKey]) on a new phone. */
    suspend fun restore(recoveryKey: String, username: String): Identity {
        val clean = recoveryKey.lowercase().filter { !it.isWhitespace() && it != '-' }
        val seed = runCatching { ZBase32.decode(clean) }.getOrNull()
        require(seed != null && seed.size == Ed25519.SEED_SIZE && ZBase32.encode(seed) == clean) { "That recovery key isn't valid" }
        return createFromSeed(username, seed)
    }

    /** The private seed as 52 letters/digits. Whoever has it can be you: shown only on request. */
    suspend fun recoveryKey(): String = withSeed { ZBase32.encode(it) }

    private suspend fun createFromSeed(username: String, seed: ByteArray): Identity = withContext(Dispatchers.Default) {
        require(Identity.isValidName(username)) { "Name must be 1–${Identity.MAX_NAME_LENGTH} characters" }
        check(store.data.first()[PUBLIC_KEY] == null) { "Identity already exists" }
        try {
            val publicKey = Ed25519.publicKeyFromSeed(seed)
            val wrapped = cipher.encrypt(seed)
            val now = System.currentTimeMillis()
            val name = Identity.cleanName(username)
            store.edit {
                it[WRAPPED_SEED] = Base64.encodeToString(wrapped, Base64.NO_WRAP)
                it[PUBLIC_KEY] = Base64.encodeToString(publicKey, Base64.NO_WRAP)
                it[USERNAME] = name
                it[CREATED_AT] = now
            }
            Identity(publicKey, name, now)
        } finally {
            seed.fill(0)
        }
    }

    /** Sets (or clears, with "") the findable @username. */
    suspend fun setHandle(handle: String) {
        val h = Identity.cleanHandle(handle)
        require(h.isEmpty() || Identity.isValidHandle(h))
        store.edit { it[HANDLE] = h }
    }

    suspend fun rename(username: String) {
        require(Identity.isValidName(username))
        store.edit { it[USERNAME] = Identity.cleanName(username) }
    }

    /** Decrypts the seed for [block] only, then wipes it. */
    suspend fun <T> withSeed(block: (ByteArray) -> T): T = withContext(Dispatchers.Default) {
        val wrapped = store.data.first()[WRAPPED_SEED] ?: error("No identity")
        val seed = cipher.decrypt(Base64.decode(wrapped, Base64.NO_WRAP))
        try {
            block(seed)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Real self-test: unwrap the seed from Keystore, sign a challenge and verify it
     * against the stored public key. Fails if the Keystore key was lost (e.g. restore
     * to a new device) or the stored data was tampered with.
     */
    suspend fun selfTest(): Boolean = runCatching {
        val publicKey = identity.first()?.publicKey ?: return false
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sig = withSeed { seed ->
            check(Ed25519.publicKeyFromSeed(seed).contentEquals(publicKey))
            Ed25519.sign(seed, challenge)
        }
        Ed25519.verify(publicKey, challenge, sig)
    }.getOrDefault(false)

    private companion object {
        val WRAPPED_SEED = stringPreferencesKey("wrapped_seed")
        val PUBLIC_KEY = stringPreferencesKey("public_key")
        val USERNAME = stringPreferencesKey("username")
        val CREATED_AT = longPreferencesKey("created_at")
        val HANDLE = stringPreferencesKey("handle")
    }
}
