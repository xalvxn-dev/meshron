package com.xalvxnlab.meshron.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiError(message: String, val status: Int) : Exception(message)

/**
 * Talks to Meshron's accounts server with a Meshron account. Like the Meshron app, the password
 * is turned into an auth key on the phone (Argon2id) and only that is sent; it's kept encrypted
 * with a key in Android's keystore.
 */
class Api(context: Context) {
    private val base = BuildConfig.ACCOUNTS_URL.trimEnd('/')
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    var username: String = prefs.getString("u", "").orEmpty(); private set
    private var auth: String = prefs.getString("a", null)?.let { runCatching { decrypt(it) }.getOrNull() }.orEmpty()
    val signedIn get() = username.isNotEmpty() && auth.isNotEmpty()

    /** Same as the Meshron app: authKey = Argon2id(password, SHA-256("meshron-account-v1:" + user)[0..16])[0..32]. */
    private fun authKey(user: String, password: String): String {
        val salt = MessageDigest.getInstance("SHA-256").digest(("meshron-account-v1:" + user).toByteArray()).copyOf(16)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3).withMemoryAsKB(64 * 1024).withParallelism(1).withSalt(salt).build()
        val out = ByteArray(64)
        val pw = password.toByteArray(Charsets.UTF_8)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, out)
        pw.fill(0)
        return out.copyOfRange(0, 32).joinToString("") { "%02x".format(it) }.also { out.fill(0) }
    }

    /** Signs in with a Meshron account (checked with a harmless call). */
    suspend fun signIn(user: String, password: String) {
        val u = user.trim().removePrefix("@").lowercase()
        val a = withContext(Dispatchers.Default) { authKey(u, password) }
        post("/v1/assistant", JSONObject().put("username", u).put("authKey", a)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Say hello in three words.")))
            .put("context", JSONObject().put("name", u)))
        username = u; auth = a
        prefs.edit().putString("u", u).putString("a", encrypt(a)).apply()
    }

    fun signOut() { username = ""; auth = ""; prefs.edit().clear().apply() }

    /** One step of a conversation: { text } or { message, done, calls }. */
    suspend fun step(messages: JSONArray, context: JSONObject, image: String?): JSONObject {
        val body = JSONObject().put("username", username).put("authKey", auth).put("messages", messages).put("context", context)
        if (image != null) body.put("image", image)
        return post("/v1/assistant", body)
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code !in 200..299) throw ApiError(o.optString("error").ifEmpty { "Something went wrong ($code)" }.let { if (code == 401) "Wrong username or password" else it }, code)
            o
        } catch (e: ApiError) { throw e } catch (e: Exception) { throw ApiError("No connection. Check the internet and try again.", 0) } finally { c.disconnect() }
    }

    // ---- Keystore ----
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("meshron.assistant", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("meshron.assistant", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(s: String): String {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
