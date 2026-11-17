package com.xalvxnlab.meshron.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.chatPrefs by preferencesDataStore(name = "chat_prefs")
private val Context.chatPrefsAnon by preferencesDataStore(name = "chat_prefs_anon")

/** Settings for one chat: disappearing timer (hours, 0 = off, follows the app setting). */
class ChatPrefs(context: Context) {
    private val store = if (com.xalvxnlab.meshron.identity.Mode.anon) context.applicationContext.chatPrefsAnon else context.applicationContext.chatPrefs
    private fun key(conv: String) = intPreferencesKey("disappear:$conv")

    fun disappearHours(conv: String): Flow<Int> = store.data.map { it[key(conv)] ?: 0 }.distinctUntilChanged()
    suspend fun setDisappearHours(conv: String, hours: Int) { store.edit { if (hours <= 0) it.remove(key(conv)) else it[key(conv)] = hours } }

    /** Every chat with its own timer: conversation -> hours. */
    suspend fun allTimers(): Map<String, Int> = store.data.first().asMap()
        .mapNotNull { (k, v) -> k.name.removePrefix("disappear:").takeIf { k.name.startsWith("disappear:") }?.let { it to (v as Int) } }.toMap()
}
