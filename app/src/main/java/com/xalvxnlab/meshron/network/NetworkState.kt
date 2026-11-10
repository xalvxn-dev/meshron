package com.xalvxnlab.meshron.network

data class NetworkState(
    val status: Status = Status.STOPPED,
    val dht: DhtInfo? = null,
    val peers: Map<String, PeerInfo> = emptyMap(),
    /** True once the worklet confirmed it derived the same public key as Kotlin. */
    val keyMatches: Boolean? = null,
    val log: List<String> = emptyList(),
    val lastError: String? = null,
) {
    enum class Status { STOPPED, STARTING, ONLINE, FAILED }
}

data class DhtInfo(
    val bootstrapped: Boolean,
    val host: String?,
    val port: Int,
    /** Behind a NAT/firewall that blocks unsolicited incoming UDP. */
    val firewalled: Boolean,
    /** NAT changes the port per destination ("hard"/symmetric NAT). Hole punching is unlikely. */
    val randomized: Boolean,
    val connections: Int,
    /** False when "Use internet" is off (nearby connections only). */
    val internet: Boolean = true,
) {
    val natLabel: String
        get() = when {
            !internet -> "Internet off"
            !bootstrapped -> "Checking…"
            !firewalled -> "Open (reachable directly)"
            randomized -> "Hard NAT (random ports)"
            else -> "Firewalled (hole punching OK)"
        }
}

data class PeerInfo(
    val keyHex: String,
    val state: State,
    val host: String? = null,
    val port: Int = 0,
    val relayed: Boolean = false,
    /** How we are connected: "internet", "lan" (same Wi-Fi, no internet needed) or "nearby" (Bluetooth / Wi-Fi Direct). */
    val via: String = "internet",
    val initiator: Boolean = false,
    val announcedName: String? = null,
    val since: Long = System.currentTimeMillis(),
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    val viaLabel: String
        get() = when (via) {
            "lan" -> "same Wi-Fi"
            "nearby" -> "Bluetooth / Wi-Fi Direct"
            else -> if (relayed) "relayed" else "internet"
        }
}
