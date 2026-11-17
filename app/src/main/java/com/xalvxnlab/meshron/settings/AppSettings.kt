package com.xalvxnlab.meshron.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")
private val Context.settingsStoreAnon by preferencesDataStore(name = "settings_anon")

/** Small app preferences (not secret). */
class AppSettings(context: Context) {
    private val store = if (com.xalvxnlab.meshron.identity.Mode.anon) context.applicationContext.settingsStoreAnon else context.applicationContext.settingsStore

    private fun flag(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        store.data.map { it[key] ?: default }.distinctUntilChanged()

    private suspend fun set(key: Preferences.Key<Boolean>, on: Boolean) {
        store.edit { it[key] = on }
    }

    /** Keep the network running in a foreground service when the app is in the background (on by default: notifications and "online" while minimised or closed). */
    val stayConnected = flag(STAY_CONNECTED, true)
    suspend fun setStayConnected(on: Boolean) = set(STAY_CONNECTED, on)

    /** Look: "system", "light" or "dark" (anonymous is always Stealth). */
    val theme: Flow<String> = store.data.map { it[THEME] ?: "system" }.distinctUntilChanged()
    suspend fun setTheme(mode: String) { store.edit { it[THEME] = mode } }

    /** Reach contacts over the internet (Hyperswarm). Off = nearby connections only. */
    val useInternet = flag(USE_INTERNET, true)
    suspend fun setUseInternet(on: Boolean) = set(USE_INTERNET, on)

    /** Find Meshron phones on the same Wi-Fi or hotspot (works without internet). */
    val sameWifi = flag(SAME_WIFI, !com.xalvxnlab.meshron.identity.Mode.anon) // anonymous: off unless switched on
    suspend fun setSameWifi(on: Boolean) = set(SAME_WIFI, on)

    /** Connect to Meshron phones nearby over Bluetooth and Wi-Fi Direct. */
    val nearby = flag(NEARBY, !com.xalvxnlab.meshron.identity.Mode.anon)
    suspend fun setNearby(on: Boolean) = set(NEARBY, on)

    /** Let people who aren't my friends send a chat request (off = only friends can reach me). */
    val allowRequests = flag(booleanPreferencesKey("allow_requests"), true)
    suspend fun setAllowRequests(on: Boolean) = set(booleanPreferencesKey("allow_requests"), on)

    /** Show messages in other languages translated into mine (sent to Meshron's AI to translate). */
    val autoTranslate = flag(booleanPreferencesKey("auto_translate"), true)
    suspend fun setAutoTranslate(on: Boolean) = set(booleanPreferencesKey("auto_translate"), on)

    /** Tell people when I've seen their messages (and see theirs). */
    val readReceipts = flag(booleanPreferencesKey("read_receipts"), true)
    suspend fun setReadReceipts(on: Boolean) = set(booleanPreferencesKey("read_receipts"), on)

    /** Newest moderation notice already looked at (Activity shows only newer ones as a count). */
    val noticesSeen: Flow<Long> = store.data.map { it[androidx.datastore.preferences.core.longPreferencesKey("notices_seen")] ?: 0L }.distinctUntilChanged()
    suspend fun setNoticesSeen(ts: Long) { store.edit { it[androidx.datastore.preferences.core.longPreferencesKey("notices_seen")] = ts } }

    /** Agreed to the Terms, Privacy Policy and Community Guidelines, and confirmed age (version). */
    val legalAccepted: Flow<Int> = store.data.map { it[androidx.datastore.preferences.core.intPreferencesKey("legal_v")] ?: 0 }.distinctUntilChanged()
    suspend fun acceptLegal(v: Int) { store.edit { it[androidx.datastore.preferences.core.intPreferencesKey("legal_v")] = v } }

    /** Store and pass on other people's encrypted messages (mesh). */
    val carryForOthers = flag(CARRY, true)
    suspend fun setCarryForOthers(on: Boolean) = set(CARRY, on)

    // ---- Privacy (anonymous mode) ----

    /** Internet connections only through relays, so contacts never see this device's IP address. */
    val hideIp = flag(HIDE_IP, com.xalvxnlab.meshron.identity.Mode.anon)
    suspend fun setHideIp(on: Boolean) = set(HIDE_IP, on)

    /** Messages delete themselves after this many hours (0 = keep). Anonymous: 24 h by default. */
    val disappearHours = store.data.map { it[DISAPPEAR] ?: if (com.xalvxnlab.meshron.identity.Mode.anon) 24 else 0 }.distinctUntilChanged()
    suspend fun setDisappearHours(h: Int) { store.edit { it[DISAPPEAR] = h } }

    /** App lock: salted SHA-256 of the PIN ("salt:hash" hex), or null when off. */
    val pinHash = store.data.map { it[PIN] }.distinctUntilChanged()
    suspend fun setPin(pin: String?) {
        store.edit { p ->
            if (pin.isNullOrEmpty()) p.remove(PIN)
            else {
                val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
                p[PIN] = hex(salt) + ":" + hex(digest(salt, pin))
            }
        }
    }
    fun pinMatches(stored: String, pin: String): Boolean {
        val (salt, hash) = stored.split(":").takeIf { it.size == 2 } ?: return false
        val s = salt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return java.security.MessageDigest.isEqual(hex(digest(s, pin)).toByteArray(), hash.toByteArray())
    }
    private fun digest(salt: ByteArray, pin: String): ByteArray {
        // Many rounds so a stolen hash can't be brute-forced quickly.
        var h = salt + pin.toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        repeat(50_000) { h = md.digest(h + salt) }
        return h
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private companion object {
        val HIDE_IP = booleanPreferencesKey("hide_ip")
        val THEME = androidx.datastore.preferences.core.stringPreferencesKey("theme")
        val DISAPPEAR = androidx.datastore.preferences.core.intPreferencesKey("disappear_hours")
        val PIN = androidx.datastore.preferences.core.stringPreferencesKey("pin")
        val STAY_CONNECTED = booleanPreferencesKey("stay_connected")
        val USE_INTERNET = booleanPreferencesKey("use_internet")
        val SAME_WIFI = booleanPreferencesKey("same_wifi")
        val NEARBY = booleanPreferencesKey("nearby")
        val CARRY = booleanPreferencesKey("carry_for_others")
    }
}
