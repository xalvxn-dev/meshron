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

/** Small app preferences (not secret). */
class AppSettings(context: Context) {
    private val store = context.applicationContext.settingsStore

    private fun flag(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        store.data.map { it[key] ?: default }.distinctUntilChanged()

    private suspend fun set(key: Preferences.Key<Boolean>, on: Boolean) {
        store.edit { it[key] = on }
    }

    /** Keep the network running in a foreground service when the app is in the background (on by default: notifications and "online" while minimised or closed). */
    val stayConnected = flag(STAY_CONNECTED, true)
    suspend fun setStayConnected(on: Boolean) = set(STAY_CONNECTED, on)

    /** Reach contacts over the internet (Hyperswarm). Off = nearby connections only. */
    val useInternet = flag(USE_INTERNET, true)
    suspend fun setUseInternet(on: Boolean) = set(USE_INTERNET, on)

    /** Find Meshron phones on the same Wi-Fi or hotspot (works without internet). */
    val sameWifi = flag(SAME_WIFI, true)
    suspend fun setSameWifi(on: Boolean) = set(SAME_WIFI, on)

    /** Connect to Meshron phones nearby over Bluetooth and Wi-Fi Direct. */
    val nearby = flag(NEARBY, true)
    suspend fun setNearby(on: Boolean) = set(NEARBY, on)

    /** Store and pass on other people's encrypted messages (mesh). */
    val carryForOthers = flag(CARRY, true)
    suspend fun setCarryForOthers(on: Boolean) = set(CARRY, on)

    private companion object {
        val STAY_CONNECTED = booleanPreferencesKey("stay_connected")
        val USE_INTERNET = booleanPreferencesKey("use_internet")
        val SAME_WIFI = booleanPreferencesKey("same_wifi")
        val NEARBY = booleanPreferencesKey("nearby")
        val CARRY = booleanPreferencesKey("carry_for_others")
    }
}
