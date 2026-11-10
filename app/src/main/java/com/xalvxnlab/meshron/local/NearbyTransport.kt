package com.xalvxnlab.meshron.local

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.xalvxnlab.meshron.network.NetworkService
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth + Wi-Fi Direct, phone to phone, no router or internet. Uses Google's Nearby
 * Connections, which finds phones over Bluetooth and moves the data to Wi-Fi Direct (or a
 * phone hotspot) when it can, for speed. The endpoint name is only a random per-run
 * session ID; the identity is verified by the Noise handshake inside the worklet, so we
 * accept every Nearby connection and let Noise decide. Nearby's own encryption is a bonus.
 */
class NearbyTransport(
    context: Context,
    private val sessionId: String,
    private val network: NetworkService,
    private val onChange: () -> Unit,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    @Volatile private var running = false
    private val links = ConcurrentHashMap<String, NetworkService.Link>() // endpoint -> link
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val early = ConcurrentHashMap<String, MutableList<ByteArray>>() // bytes before the link exists

    @Volatile var error: String? = null
        private set

    val connectionCount: Int get() = links.size

    @Synchronized
    fun start() {
        if (running) return
        running = true
        error = null
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(sessionId, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("advertising", it) }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("discovery", it) }
        Log.i(TAG, "Bluetooth / Wi-Fi Direct started as $sessionId")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        links.values.forEach { it.closed() }
        links.clear()
        requested.clear()
        early.clear()
        onChange()
    }

    private fun fail(what: String, t: Exception) {
        error = t.message ?: t.javaClass.simpleName
        Log.w(TAG, "Nearby $what failed: $error")
        onChange()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID || links.containsKey(endpointId)) return
            Log.i(TAG, "Found phone ${info.endpointName} nearby")
            // The smaller session ID asks; the other side only accepts.
            if (sessionId < info.endpointName && requested.add(endpointId)) {
                client.requestConnection(sessionId, endpointId, lifecycle).addOnFailureListener {
                    requested.remove(endpointId)
                    Log.w(TAG, "Connection request failed: ${it.message}")
                }
            }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                requested.remove(endpointId)
                return
            }
            val link = network.openLink("nearby", endpointId in requested, object : NetworkService.LinkSink {
                override fun write(bytes: ByteArray) {
                    var i = 0
                    while (i < bytes.size) {
                        val end = minOf(bytes.size, i + ConnectionsClient.MAX_BYTES_DATA_SIZE)
                        client.sendPayload(endpointId, Payload.fromBytes(bytes.copyOfRange(i, end)))
                        i = end
                    }
                }

                override fun close() {
                    client.disconnectFromEndpoint(endpointId)
                    dropped(endpointId)
                }
            })
            links[endpointId] = link
            early.remove(endpointId)?.forEach(link::receive)
            Log.i(TAG, "Connected to a phone over Bluetooth / Wi-Fi Direct")
            onChange()
        }

        override fun onDisconnected(endpointId: String) {
            links[endpointId]?.closed()
            dropped(endpointId)
        }
    }

    private fun dropped(endpointId: String) {
        links.remove(endpointId)
        requested.remove(endpointId)
        early.remove(endpointId)
        onChange()
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val link = links[endpointId]
            if (link != null) link.receive(bytes)
            else early.getOrPut(endpointId) { mutableListOf() }.add(bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private companion object {
        const val TAG = "NearbyTransport"
        const val SERVICE_ID = "com.xalvxnlab.meshron.mesh"
    }
}
