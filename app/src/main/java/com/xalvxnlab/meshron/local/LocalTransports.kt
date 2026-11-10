package com.xalvxnlab.meshron.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.NetworkState
import com.xalvxnlab.meshron.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** What the offline transports are doing, for the Profile screen. */
data class LocalStatus(
    val sameWifi: Boolean = false,
    val nearby: Boolean = false,
    val nearbyMissingPermission: Boolean = false,
    val lanMissingPermission: Boolean = false,
    val nearbyError: String? = null,
    val lanLinks: Int = 0,
    val nearbyLinks: Int = 0,
)

/**
 * Runs the no-internet transports (same Wi-Fi, Bluetooth / Wi-Fi Direct) according to the
 * settings, once the worklet is ready. Both feed byte pipes into the worklet, which treats
 * them like any other connection (Noise handshake, sync, mesh).
 */
class LocalTransports(
    private val context: Context,
    private val network: NetworkService,
    private val settings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Random per run; announced locally instead of the user's ID. */
    private val sessionId = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private val lan = LanTransport(context, sessionId, network, ::refresh)
    private val nearby = NearbyTransport(context, sessionId, network, ::refresh)
    private val _status = MutableStateFlow(LocalStatus())
    val status: StateFlow<LocalStatus> = _status.asStateFlow()
    private val permissionCheck = MutableStateFlow(0)

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val ready = network.state.map { it.status == NetworkState.Status.ONLINE }.distinctUntilChanged()
            combine(ready, settings.sameWifi, settings.nearby, permissionCheck) { r, wifi, near, _ -> Triple(r, wifi, near) }
                .collect { (r, wifi, near) ->
                    runCatching { if (r && wifi && hasLanPermission(context)) lan.start() else lan.stop() }
                        .onFailure { Log.w(TAG, "Same-Wi-Fi transport: ${it.message}") }
                    val allowed = hasNearbyPermissions(context)
                    runCatching { if (r && near && allowed) nearby.start() else nearby.stop() }
                        .onFailure { Log.w(TAG, "Nearby transport: ${it.message}") }
                    refresh()
                }
        }
    }

    /** Call after the user answered the permission prompt. */
    fun permissionsChanged() {
        permissionCheck.value++
    }

    @Volatile private var lanOn = false
    @Volatile private var nearbyOn = false

    private fun refresh() {
        _status.value = LocalStatus(
            sameWifi = lanOn,
            nearby = nearbyOn,
            nearbyMissingPermission = !hasNearbyPermissions(context),
            lanMissingPermission = !hasLanPermission(context),
            nearbyError = nearby.error,
            lanLinks = lan.connectionCount,
            nearbyLinks = nearby.connectionCount,
        )
    }

    init {
        scope.launch { settings.sameWifi.collect { lanOn = it; refresh() } }
        scope.launch { settings.nearby.collect { nearbyOn = it; refresh() } }
    }

    companion object {
        private const val TAG = "LocalTransports"

        /** Runtime permissions Nearby Connections needs on this Android version. */
        fun nearbyPermissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            // Nearby Connections checks for location even on Android 12+ (it never reads it).
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

        /** Android 17+ asks before an app may talk to devices on the local network. */
        fun lanPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 37) arrayOf("android.permission.ACCESS_LOCAL_NETWORK") else emptyArray()

        /** Everything to ask for in one prompt. */
        fun allPermissions(): Array<String> = lanPermissions() + nearbyPermissions()

        fun hasLanPermission(context: Context) = lanPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun hasNearbyPermissions(context: Context) = nearbyPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
