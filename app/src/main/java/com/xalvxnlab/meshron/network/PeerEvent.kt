package com.xalvxnlab.meshron.network

/** Events from the worklet that other layers (messaging, groups, later files) react to. */
sealed interface PeerEvent {
    /** The worklet joined the DHT and can append to logs. */
    data object Ready : PeerEvent

    data class Connected(val key: String) : PeerEvent
    data class Disconnected(val key: String) : PeerEvent

    /**
     * Entry [seq] of the peer's 1:1 outbox log for us. Its signature was already verified by
     * the worklet against the peer's identity key.
     */
    data class Message(
        val key: String,
        val seq: Long,
        val id: String,
        val sentAt: Long,
        val body: String,
        val kind: String,
        val signature: String,
    ) : PeerEvent

    /** Our message [id] is now entry [seq] of our outbox log for [key]. */
    data class Appended(val key: String, val id: String, val seq: Long) : PeerEvent

    /** The peer has stored every entry of our outbox below [length]. */
    data class Delivered(val key: String, val length: Long) : PeerEvent

    /** Entry [seq] of member [from]'s log in [group], signature verified. */
    data class GroupMessage(
        val group: String,
        val from: String,
        val seq: Long,
        val id: String,
        val sentAt: Long,
        val body: String,
        val kind: String,
        val signature: String,
    ) : PeerEvent

    /** Learned (and verified) [member]'s log key in [group]; [sig] is the member's signature. */
    data class GroupLogLearned(val group: String, val member: String, val outbox: String, val sig: String) : PeerEvent

    /** An upload ([xfer]) is stored in my blob log [blobs]; [blob] is the Hyperblobs id (JSON). */
    data class FileStored(val xfer: String, val blobs: String, val blob: String) : PeerEvent
    data class FileData(val req: String, val data: ByteArray) : PeerEvent
    data class FileDone(val req: String) : PeerEvent
    /** [id] is the xfer of an upload or the req of a download. */
    data class FileError(val id: String, val message: String) : PeerEvent

    data class GroupAppended(val group: String, val id: String, val seq: Long) : PeerEvent

    /** [member] has stored every entry of my log in [group] below [length]. */
    data class GroupDelivered(val group: String, val member: String, val length: Long) : PeerEvent
}
