package com.xalvxnlab.meshron.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.xalvxnlab.meshron.network.NetworkService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Same Wi-Fi / hotspot, no internet needed. Each phone runs a small TCP server and
 * announces it on the local network with mDNS ("_meshron._tcp"). The service name carries
 * only a random per-run session ID, never the user's identity; who is on the other end is
 * proven by the Noise handshake inside the worklet. The phone with the smaller session ID
 * dials, so two phones make one connection, not two.
 */
class LanTransport(
    private val context: Context,
    private val sessionId: String,
    private val network: NetworkService,
    private val onChange: () -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = ConcurrentHashMap<String, NsdServiceInfo>() // session ID -> service
    private val active = ConcurrentHashMap<String, Socket>() // session ID -> socket we dialled
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    val connectionCount: Int get() = sockets.size

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val ss = ServerSocket(0)
        server = ss
        thread(name = "lan-accept", isDaemon = true) {
            while (running) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                attach(s, initiator = false, sid = null)
            }
        }
        register(ss.localPort)
        discover()
        thread(name = "lan-dial", isDaemon = true) {
            while (running) {
                for ((sid, info) in found) {
                    if (!running) break
                    if (sid > sessionId && !active.containsKey(sid)) dial(sid, info)
                }
                Thread.sleep(4000)
            }
        }
        Log.i(TAG, "Same-Wi-Fi discovery started (port ${ss.localPort})")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        runCatching { server?.close() }
        server = null
        sockets.forEach { runCatching { it.close() } }
        found.clear()
        onChange()
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = PREFIX + sessionId
            serviceType = TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Announced as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "Announce failed: $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "Discovery failed: $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                val sid = info.serviceName.removePrefix(PREFIX)
                if (info.serviceName.startsWith(PREFIX) && sid != sessionId) {
                    found[sid] = info
                    Log.i(TAG, "Found phone $sid on this network")
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName.removePrefix(PREFIX))
            }
        }
        discovery = listener
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Resolves the service (one at a time, NSD allows no more) and connects. */
    private fun dial(sid: String, info: NsdServiceInfo) {
        val latch = CountDownLatch(1)
        var resolved: NsdServiceInfo? = null
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = latch.countDown()
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolved = info
                latch.countDown()
            }
        })
        latch.await(10, TimeUnit.SECONDS)
        val r = resolved ?: return
        val host: InetAddress = (if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull() else null)
            ?: @Suppress("DEPRECATION") r.host ?: return
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, r.port), 5000)
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't reach $sid at $host:${r.port}: ${t.message}")
            runCatching { socket.close() }
            return
        }
        active[sid] = socket
        attach(socket, initiator = true, sid = sid)
    }

    private fun attach(socket: Socket, initiator: Boolean, sid: String?) {
        socket.tcpNoDelay = true
        sockets += socket
        onChange()
        val out = socket.getOutputStream()
        val writer = Executors.newSingleThreadExecutor()
        lateinit var link: NetworkService.Link
        fun cleanup() {
            sockets -= socket
            if (sid != null) active.remove(sid, socket)
            runCatching { socket.close() }
            writer.shutdown()
            onChange()
        }
        link = network.openLink("lan", initiator, object : NetworkService.LinkSink {
            override fun write(bytes: ByteArray) {
                runCatching {
                    writer.execute {
                        runCatching { out.write(bytes); out.flush() }.onFailure { cleanup() }
                    }
                }
            }
            override fun close() = cleanup()
        })
        thread(name = "lan-read", isDaemon = true) {
            val input = socket.getInputStream()
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    link.receive(buf.copyOf(n))
                }
            } catch (_: Throwable) {
            }
            cleanup()
            link.closed()
        }
    }

    private companion object {
        const val TAG = "LanTransport"
        const val TYPE = "_meshron._tcp."
        const val PREFIX = "meshron-"
    }
}
