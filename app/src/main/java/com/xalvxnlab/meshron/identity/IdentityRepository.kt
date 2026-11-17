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
private val Context.identityStoreAnon by preferencesDataStore(name = "identity_anon")

/**
 * Owns the user's cryptographic identity. The private seed is only ever stored
 * encrypted by [KeystoreCipher]; plaintext copies are zeroed after use.
 */
class IdentityRepository(context: Context) {
    private val store = if (Mode.anon) context.applicationContext.identityStoreAnon else context.applicationContext.identityStore
    private val cipher = if (Mode.anon) KeystoreCipher("meshron.identity.wrap.anon") else KeystoreCipher()

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

    /** Sets up this phone with a seed from a public account (sign-up or sign-in). Wipes [seed]. */
    suspend fun adopt(seed: ByteArray, name: String, handle: String): Identity {
        val id = createFromSeed(name.ifBlank { handle }, seed)
        setHandle(handle)
        return id
    }

    /** Sets (or clears, with "") the findable @username. [byUser]: a change the person made (starts the 30-day wait). */
    suspend fun setHandle(handle: String, byUser: Boolean = false) {
        val h = Identity.cleanHandle(handle)
        require(h.isEmpty() || Identity.isValidHandle(h))
        if (byUser) nextHandleChange().let { if (it > System.currentTimeMillis()) throw IllegalStateException(waitText("username", it)) }
        store.edit { it[HANDLE] = h; if (byUser) it[HANDLE_AT] = System.currentTimeMillis() }
    }

    suspend fun rename(username: String) {
        require(Identity.isValidName(username))
        nextNameChange().let { if (it > System.currentTimeMillis()) throw IllegalStateException(waitText("name", it)) }
        store.edit { it[USERNAME] = Identity.cleanName(username); it[NAME_AT] = System.currentTimeMillis() }
    }

    /** When the name may change again (once every 7 days). 0 = now. */
    suspend fun nextNameChange(): Long = store.data.first()[NAME_AT]?.let { it + NAME_EVERY } ?: 0L

    /** When the @username may change again (once every 30 days). 0 = now. */
    suspend fun nextHandleChange(): Long = store.data.first()[HANDLE_AT]?.let { it + HANDLE_EVERY } ?: 0L

    private fun waitText(what: String, at: Long): String =
        "You can change your $what again on " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))

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
        val NAME_AT = longPreferencesKey("name_changed_at")
        val HANDLE_AT = longPreferencesKey("handle_changed_at")
        const val NAME_EVERY = 7L * 24 * 60 * 60 * 1000
        const val HANDLE_EVERY = 30L * 24 * 60 * 60 * 1000
        val CREATED_AT = longPreferencesKey("created_at")
        val HANDLE = stringPreferencesKey("handle")
    }
}
