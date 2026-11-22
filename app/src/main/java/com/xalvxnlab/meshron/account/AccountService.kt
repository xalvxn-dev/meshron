package com.xalvxnlab.meshron.account

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xalvxnlab.meshron.BuildConfig
import com.xalvxnlab.meshron.crypto.Ed25519
import com.xalvxnlab.meshron.crypto.KeystoreCipher
import com.xalvxnlab.meshron.identity.IdentityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

private val Context.accountStore by preferencesDataStore(name = "account")

/** Public accounts: @username + password, email codes, optional 2-step. Never used when anonymous. */
class AccountService(context: Context, private val identity: IdentityRepository) {
    private val store = context.applicationContext.accountStore
    private val cipher = KeystoreCipher("meshron.account.wrap")

    data class State(val username: String, val email: String, val twoFactor: Boolean)
    val state: Flow<State?> = store.data.map { p ->
        val u = p[USER] ?: return@map null
        State(u, p[EMAIL].orEmpty(), p[TWO_FACTOR] ?: false)
    }

    class ApiError(msg: String, val status: Int) : Exception(msg)

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(BuildConfig.ACCOUNTS_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: "{}"
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code >= 400 || o.has("error")) throw ApiError(o.optString("error", "Something went wrong ($code)"), code)
            o
        } catch (e: java.io.IOException) {
            throw ApiError("No connection to Meshron accounts. Check your internet.", 0)
        } finally {
            c.disconnect()
        }
    }

    /** A call made as my signed-in account (moderators' tools). */
    /** A signed-in request to an AI route. */
    suspend fun ai2(path: String, body: JSONObject): JSONObject = postAsMe(path, body)

    suspend fun postAsMe(path: String, body: JSONObject): JSONObject { val (u, a) = auth(); return post(path, body.put("username", u).put("authKey", a)) }

    /** Any account-server call (moderation uses it with its own signed bodies). */
    suspend fun postRaw(path: String, body: JSONObject): JSONObject = post(path, body)

    suspend fun available(username: String): Boolean = post("/v1/check", JSONObject().put("username", username)).optBoolean("available")
    /** A username kept for someone (needs an invite code). */
    suspend fun reserved(username: String): Boolean = post("/v1/check", JSONObject().put("username", username)).optBoolean("reserved")

    /** Public key for @username, or null if nobody has it. */
    suspend fun resolve(username: String): String? = runCatching {
        post("/v1/resolve", JSONObject().put("username", username)).optString("publicKey").takeIf { it.length == 64 }
    }.getOrNull()

    suspend fun sendSignupCode(email: String) { post("/v1/signup/code", JSONObject().put("email", email)) }

    /**
     * Creates the account. With [existing] = true, the ID already on this phone gets the
     * username and password (nothing is lost); otherwise a new ID is made.
     */
    /** Keeps the birthday with the account (so a new phone or a login doesn't ask again). */
    suspend fun saveBirthday(date: java.time.LocalDate) { runCatching { postAsMe("/v1/account/birth", JSONObject().put("birth", date.toString())) } }

    suspend fun signUp(name: String, username: String, email: String, password: String, code: String, existing: Boolean = false, invite: String = "") {
        val birthday = com.xalvxnlab.meshron.settings.Age.birthday?.toString()
        val u = username.trim().removePrefix("@").lowercase()
        val keys = withContext(Dispatchers.Default) { AccountCrypto.derive(u, password) }
        val seed = if (existing) identity.withSeed { it.copyOf() } else ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val pk = Ed25519.publicKeyFromSeed(seed)
            val sig = Ed25519.sign(seed, "meshron-signup-v1:$u".toByteArray())
            post(
                "/v1/signup", JSONObject().put("username", u).put("email", email.trim()).put("code", code.trim())
                    .put("authKey", keys.authHex).put("blob", AccountCrypto.lock(keys.lockKey, seed))
                    .put("publicKey", pk.hex()).put("sig", sig.hex()).apply { if (invite.isNotBlank()) put("invite", invite.trim()) },
            )
            // Remember the account first, and finish even if the screen goes away: adopting the new
            // ID switches the app to the home screen, which used to cancel this before the account
            // was saved (so people were asked to make a second account).
            withContext(kotlinx.coroutines.NonCancellable) {
                remember(u, email.trim(), keys.authHex, false)
                birthday?.let { runCatching { post("/v1/account/birth", JSONObject().put("username", u).put("authKey", keys.authHex).put("birth", it)) } }
                if (existing) identity.setHandle(u) else identity.adopt(seed.copyOf(), name, u)
            }
        } finally {
            seed.fill(0); keys.wipe()
        }
    }

    sealed interface Login {
        data object Done : Login
        data class NeedCode(val email: String) : Login
    }

    private var pendingKeys: AccountCrypto.Keys? = null
    private var pendingUser = ""

    suspend fun logIn(username: String, password: String): Login {
        val u = username.trim().removePrefix("@").lowercase()
        val keys = withContext(Dispatchers.Default) { AccountCrypto.derive(u, password) }
        val r = post("/v1/login", JSONObject().put("username", u).put("authKey", keys.authHex))
        if (r.optBoolean("needCode")) {
            pendingKeys?.wipe()
            pendingKeys = keys
            pendingUser = u
            return Login.NeedCode(r.optString("email"))
        }
        finish(u, keys, r)
        return Login.Done
    }

    suspend fun logInCode(code: String) {
        val keys = pendingKeys ?: throw ApiError("Start again", 0)
        val r = post("/v1/login/code", JSONObject().put("username", pendingUser).put("authKey", keys.authHex).put("code", code.trim()))
        finish(pendingUser, keys, r)
        pendingKeys = null
    }

    private suspend fun finish(u: String, keys: AccountCrypto.Keys, r: JSONObject) {
        val seed = try { AccountCrypto.unlock(keys.lockKey, r.getString("blob")) } catch (e: Exception) { throw ApiError("Couldn't open the account", 0) }
        try {
            check(Ed25519.publicKeyFromSeed(seed).hex() == r.optString("publicKey")) { "Account data doesn't match" }
            // Same as sign-up: save the account before the app switches to the home screen.
            withContext(kotlinx.coroutines.NonCancellable) {
                remember(u, r.optString("email"), keys.authHex, r.optBoolean("twoFactor"))
                // The birthday comes with the account, so it isn't asked again on a new phone.
                r.optString("birth").takeIf { it.length == 10 }?.let { b -> runCatching { com.xalvxnlab.meshron.settings.Age.set(java.time.LocalDate.parse(b)) } }
                identity.adopt(seed.copyOf(), r.optString("name").ifBlank { u }, u)
            }
        } finally {
            seed.fill(0); keys.wipe()
        }
    }

    private suspend fun remember(u: String, email: String, authHex: String, twoFactor: Boolean) {
        val wrapped = android.util.Base64.encodeToString(cipher.encrypt(authHex.toByteArray()), android.util.Base64.NO_WRAP)
        store.edit { it[USER] = u; it[EMAIL] = email; it[AUTH] = wrapped; it[TWO_FACTOR] = twoFactor }
    }

    private suspend fun auth(): Pair<String, String> {
        val p = store.data.first()
        val u = p[USER] ?: throw ApiError("Not signed in", 0)
        val a = cipher.decrypt(android.util.Base64.decode(p[AUTH], android.util.Base64.NO_WRAP)).decodeToString()
        return u to a
    }

    /** Sends a security code (needed to turn 2-step off, change password, delete). */
    suspend fun sendSecurityCode(): String {
        val (u, a) = auth()
        return post("/v1/account/code", JSONObject().put("username", u).put("authKey", a)).optString("email")
    }

    suspend fun setTwoFactor(on: Boolean, code: String? = null) {
        val (u, a) = auth()
        val body = JSONObject().put("username", u).put("authKey", a).put("twoFactor", on)
        if (code != null) body.put("code", code.trim())
        val r = post("/v1/account", body)
        store.edit { it[TWO_FACTOR] = r.optBoolean("twoFactor") }
    }

    suspend fun changePassword(newPassword: String, code: String) {
        val (u, a) = auth()
        val keys = withContext(Dispatchers.Default) { AccountCrypto.derive(u, newPassword) }
        try {
            val blob = identity.withSeed { AccountCrypto.lock(keys.lockKey, it) }
            post("/v1/account", JSONObject().put("username", u).put("authKey", a).put("code", code.trim()).put("newAuthKey", keys.authHex).put("newBlob", blob))
            val p = store.data.first()
            remember(u, p[EMAIL].orEmpty(), keys.authHex, p[TWO_FACTOR] ?: false)
        } finally {
            keys.wipe()
        }
    }

    /** New @username. Needs the password: the keys that lock the account are made from both. */
    suspend fun rename(newUsername: String, password: String) {
        val (u, a) = auth()
        val n = newUsername.trim().removePrefix("@").lowercase()
        identity.nextHandleChange().let { if (it > System.currentTimeMillis()) throw ApiError("You can change your username again on " + java.text.DateFormat.getDateInstance().format(java.util.Date(it)), 0) }
        val old = withContext(Dispatchers.Default) { AccountCrypto.derive(u, password) }
        try { if (old.authHex != a) throw ApiError("Wrong password", 401) } finally { old.wipe() }
        val keys = withContext(Dispatchers.Default) { AccountCrypto.derive(n, password) }
        try {
            val blob = identity.withSeed { AccountCrypto.lock(keys.lockKey, it) }
            val r = post("/v1/rename", JSONObject().put("username", u).put("authKey", a).put("newUsername", n).put("newAuthKey", keys.authHex).put("newBlob", blob))
            val p = store.data.first()
            remember(r.optString("username", n), p[EMAIL].orEmpty(), keys.authHex, p[TWO_FACTOR] ?: false)
            identity.setHandle(n, byUser = true)
        } finally {
            keys.wipe()
        }
    }

    /** Runs an AI task on the account server (signed-in public accounts only). */
    suspend fun ai(task: String, body: JSONObject): JSONObject {
        val (u, a) = auth()
        return post("/v1/ai", body.put("username", u).put("authKey", a).put("task", task))
    }

    suspend fun deleteAccount(code: String) {
        val (u, a) = auth()
        post("/v1/delete", JSONObject().put("username", u).put("authKey", a).put("code", code.trim()))
        store.edit { it.clear() }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        val USER = stringPreferencesKey("user")
        val EMAIL = stringPreferencesKey("email")
        val AUTH = stringPreferencesKey("auth")
        val TWO_FACTOR = booleanPreferencesKey("two_factor")
    }
}
