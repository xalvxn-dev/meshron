package com.xalvxnlab.meshron.contacts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import android.util.Base64
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.xalvxnlab.meshron.crypto.KeystoreCipher
import org.json.JSONArray
import org.json.JSONObject

private val Context.contactsStore by preferencesDataStore(name = "contacts")

/**
 * Small contact list kept on the device. Stored encrypted with an Android Keystore key
 * (who you talk to is sensitive too); a list saved before Phase 7 is re-saved encrypted.
 */
class ContactsRepository(context: Context) {
    private val store = context.applicationContext.contactsStore
    private val cipher = KeystoreCipher("meshron.contacts.wrap.v1")

    // Decrypting goes through the Keystore, so remember the last result.
    @Volatile private var cache: Pair<String, List<Contact>>? = null

    val contacts: Flow<List<Contact>> = store.data.map { read(it) }

    /** Re-saves a contact list from before Phase 7 in encrypted form. */
    suspend fun encryptLegacy() {
        store.edit { prefs -> if (prefs[LEGACY_KEY] != null) write(prefs, read(prefs)) }
    }

    /** Adds (or upgrades a pending request to) a contact you trust. */
    suspend fun add(keyHex: String, name: String) = mutate { list ->
        val existing = list.find { it.keyHex == keyHex }
        if (existing != null) {
            list.map {
                if (it.keyHex == keyHex) it.copy(status = Contact.Status.ADDED, name = name.ifBlank { it.name }) else it
            }
        } else {
            list + Contact(keyHex, name, Contact.Status.ADDED, System.currentTimeMillis())
        }
    }

    /** Records an unknown peer that connected to us. No-op if already known. */
    suspend fun addRequest(keyHex: String) = mutate { list ->
        if (list.any { it.keyHex == keyHex }) list
        else list + Contact(keyHex, "", Contact.Status.REQUEST, System.currentTimeMillis())
    }

    /** Stores the name a peer announced about itself. */
    suspend fun updateName(keyHex: String, name: String) = mutate { list ->
        list.map { if (it.keyHex == keyHex && it.name != name) it.copy(name = name) else it }
    }

    /** Stores the @username a peer announced about itself. */
    suspend fun updateHandle(keyHex: String, handle: String) = mutate { list ->
        list.map { if (it.keyHex == keyHex && it.handle != handle) it.copy(handle = handle) else it }
    }

    suspend fun block(keyHex: String) = mutate { list ->
        if (list.any { it.keyHex == keyHex }) list.map { if (it.keyHex == keyHex) it.copy(status = Contact.Status.BLOCKED) else it }
        else list + Contact(keyHex, "", Contact.Status.BLOCKED, System.currentTimeMillis())
    }

    suspend fun unblock(keyHex: String) = mutate { list ->
        list.map { if (it.keyHex == keyHex && it.status == Contact.Status.BLOCKED) it.copy(status = Contact.Status.ADDED) else it }
    }

    suspend fun setVerified(keyHex: String, verified: Boolean) = mutate { list ->
        list.map { if (it.keyHex == keyHex) it.copy(verified = verified) else it }
    }

    suspend fun remove(keyHex: String) = mutate { list -> list.filterNot { it.keyHex == keyHex } }

    private suspend fun mutate(block: (List<Contact>) -> List<Contact>) {
        store.edit { prefs -> write(prefs, block(read(prefs))) }
    }

    private fun read(prefs: Preferences): List<Contact> {
        val enc = prefs[KEY] ?: return decode(prefs[LEGACY_KEY])
        cache?.let { (blob, list) -> if (blob == enc) return list }
        val json = String(cipher.decrypt(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        return decode(json).also { cache = enc to it }
    }

    private fun write(prefs: MutablePreferences, list: List<Contact>) {
        val enc = Base64.encodeToString(cipher.encrypt(encode(list).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs[KEY] = enc
        prefs.remove(LEGACY_KEY)
        cache = enc to list
    }

    private fun encode(list: List<Contact>): String = JSONArray().apply {
        list.forEach {
            put(
                JSONObject()
                    .put("key", it.keyHex)
                    .put("name", it.name)
                    .put("status", it.status.name)
                    .put("addedAt", it.addedAt)
                    .put("handle", it.handle)
                    .put("verified", it.verified)
            )
        }
    }.toString()

    private fun decode(json: String?): List<Contact> {
        if (json.isNullOrEmpty()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Contact(
                keyHex = o.getString("key"),
                name = o.optString("name"),
                status = runCatching { Contact.Status.valueOf(o.getString("status")) }
                    .getOrDefault(Contact.Status.REQUEST),
                addedAt = o.optLong("addedAt"),
                handle = o.optString("handle"),
                verified = o.optBoolean("verified"),
            )
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("contacts_enc")
        val LEGACY_KEY = stringPreferencesKey("contacts_json")
    }
}
