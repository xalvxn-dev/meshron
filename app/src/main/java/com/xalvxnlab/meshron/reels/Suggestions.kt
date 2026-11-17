package com.xalvxnlab.meshron.reels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xalvxnlab.meshron.contacts.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Context.suggestStore by preferencesDataStore(name = "suggestions")

data class Suggestion(val key: String, val name: String, val handle: String, val reason: String)

/**
 * "People you may know": opt-in, from people in the same rough area (~5 km geohash cell).
 * The exact location never leaves the phone; only the cell's hash is used as a meeting topic.
 */
class Suggestions(private val context: Context, private val net: com.xalvxnlab.meshron.network.NetworkService, private val reels: ReelsService, private val contacts: ContactsRepository) {
    private val store = context.applicationContext.suggestStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _list = MutableStateFlow<List<Suggestion>>(emptyList())
    val list: StateFlow<List<Suggestion>> = _list
    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

    init {
        if (!com.xalvxnlab.meshron.identity.Mode.anon) {
            reels.onNearPerson = { o -> scope.launch { found(o) } }
            // While on: (re)announce the area every 15 minutes, as people move.
            scope.launch {
                reels.ready.collect { ch ->
                    if (ch == null) return@collect
                    while (true) {
                        refresh()
                        delay(15 * 60_000L)
                    }
                }
            }
        }
    }

    suspend fun setEnabled(on: Boolean) {
        store.edit { it[ENABLED] = on }
        if (!on) _list.value = emptyList()
        refresh()
    }

    fun dismiss(key: String) {
        _list.value = _list.value.filterNot { it.key == key }
        scope.launch { store.edit { it[DISMISSED] = (it[DISMISSED] ?: emptySet()) + key } }
    }

    /** Looks around again (pull to refresh in Activity). */
    suspend fun refresh() {
        val on = enabled.first()
        val cell = if (on) currentCell() else null
        runCatching { net.reelsSend(JSONObject().put("cmd", "reels-near").put("cell", cell ?: JSONObject.NULL)) }
    }

    private suspend fun found(o: JSONObject) {
        val key = o.optString("key")
        if (key.length != 64 || !enabled.first()) return
        if ((store.data.first()[DISMISSED] ?: emptySet()).contains(key)) return
        if (contacts.contacts.first().any { it.keyHex == key }) return
        val handle = o.optString("handle")
        val s = Suggestion(key, o.optString("name").ifBlank { if (handle.isNotEmpty()) "@$handle" else "Someone nearby" }, handle, if (handle.isNotEmpty()) "@$handle · near you" else "Near you")
        _list.value = (listOf(s) + _list.value.filterNot { it.key == key }).take(50)
    }

    /** Rough area: geohash with 5 characters (about 4.9 × 4.9 km). */
    private fun currentCell(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = runCatching {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return geohash(loc.latitude, loc.longitude, 5)
    }

    companion object {
        private val ENABLED = booleanPreferencesKey("near_enabled")
        private val DISMISSED = stringSetPreferencesKey("dismissed")

        fun geohash(lat: Double, lon: Double, len: Int): String {
            val chars = "0123456789bcdefghjkmnpqrstuvwxyz"
            var latR = doubleArrayOf(-90.0, 90.0)
            var lonR = doubleArrayOf(-180.0, 180.0)
            val sb = StringBuilder()
            var bit = 0; var ch = 0; var even = true
            while (sb.length < len) {
                val r = if (even) lonR else latR
                val v = if (even) lon else lat
                val mid = (r[0] + r[1]) / 2
                if (v >= mid) { ch = ch or (1 shl (4 - bit)); r[0] = mid } else r[1] = mid
                even = !even
                if (++bit == 5) { sb.append(chars[ch]); bit = 0; ch = 0 }
            }
            return sb.toString()
        }
    }
}
