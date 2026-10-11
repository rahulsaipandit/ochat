package com.bitchat.android.sync

import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.withLock

import com.bitchat.android.protocol.nowMillis

import com.bitchat.android.protocol.PlatformLog
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import com.bitchat.android.protocol.ConcurrentMap

/**
 * Gossip-based synchronization manager using on-demand GCS filters.
 * Tracks seen public packets (ANNOUNCE, broadcast MESSAGE) and periodically requests sync
 * from neighbors. Responds to REQUEST_SYNC by sending missing packets.
 */
class GossipSyncManager(
    private val myPeerID: String,
    private val scope: CoroutineScope,
    private val configProvider: ConfigProvider
) {
    interface Delegate {
        fun sendPacket(packet: BitchatPacket)
        fun sendPacketToPeer(peerID: String, packet: BitchatPacket)
        fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket
    }

    interface ConfigProvider {
        fun seenCapacity(): Int // max packets we sync per request (cap across types)
        fun gcsMaxBytes(): Int
        fun gcsTargetFpr(): Double // percent -> 0.0..1.0
    }

    companion object {
        private const val TAG = "GossipSyncManager"
    }

    var delegate: Delegate? = null

    // Defaults (configurable constants)
    private val defaultMaxBytes = SyncDefaults.DEFAULT_FILTER_BYTES
    private val defaultFpr = SyncDefaults.DEFAULT_FPR_PERCENT

    // Stored packets for sync:
    // - broadcast messages: keep up to seenCapacity() most recent, keyed by packetId
    private val messages = LinkedHashMap<String, BitchatPacket>()
    private val messagesLock = PlatformLock()
    private val lock = PlatformLock() // formerly the monitor behind @Synchronized
    // - announcements: only keep latest per sender peerID
    private val latestAnnouncementByPeer = ConcurrentMap<String, Pair<String, BitchatPacket>>()

    private var periodicJob: Job? = null
    private var cleanupJob: Job? = null
    fun start() {
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30_000)
                    sendRequestSync()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { PlatformLog.e(TAG, "Periodic sync error: ${e.message}") }
            }
        }

        // Start periodic cleanup of stale announcements and messages
        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(com.bitchat.android.util.AppConstants.Sync.CLEANUP_INTERVAL_MS)
                    pruneStaleAnnouncements()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { PlatformLog.e(TAG, "Periodic cleanup error: ${e.message}") }
            }
        }
    }

    fun stop() {
        periodicJob?.cancel(); periodicJob = null
        cleanupJob?.cancel(); cleanupJob = null
    }

    fun clear() {
        lock.withLock {
        messagesLock.withLock {
            messages.clear()
        }
        latestAnnouncementByPeer.clear()
        PlatformLog.d(TAG, "Cleared all gossip sync messages and announcements")
    }
    }

    fun scheduleInitialSync(delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSync()
        }
    }

    fun scheduleInitialSyncToPeer(peerID: String, delayMs: Long = 5_000L) {
        scope.launch(Dispatchers.IO) {
            delay(delayMs)
            sendRequestSyncToPeer(peerID)
        }
    }

    fun onPublicPacketSeen(packet: BitchatPacket) {
        // Only ANNOUNCE or broadcast MESSAGE
        val mt = MessageType.fromValue(packet.type)
        val isBroadcastMessage = (mt == MessageType.MESSAGE && (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)))
        val isAnnouncement = (mt == MessageType.ANNOUNCE)
        if (!isBroadcastMessage && !isAnnouncement) return

        val idBytes = PacketIdUtil.computeIdBytes(packet)
        val id = idBytes.toHexString()

        if (isBroadcastMessage) {
            messagesLock.withLock {
                messages[id] = packet
                // Enforce capacity (remove oldest when exceeded)
                val cap = configProvider.seenCapacity().coerceAtLeast(1)
                while (messages.size > cap) {
                    val it = messages.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
        } else if (isAnnouncement) {
            // Ignore stale announcements older than STALE_PEER_TIMEOUT
            val now = nowMillis()
            val age = now - packet.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                PlatformLog.d(TAG, "Ignoring stale ANNOUNCE (age=${age}ms > ${com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS}ms)")
                return
            }
            // senderID is fixed-size 8 bytes; map to hex string for key
            val sender = packet.senderID.toHexString()
            latestAnnouncementByPeer[sender] = id to packet
            // Enforce capacity (remove oldest when exceeded)
            val cap = configProvider.seenCapacity().coerceAtLeast(1)
            while (latestAnnouncementByPeer.size > cap) {
                val it = latestAnnouncementByPeer.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() } else break
            }
        }
    }

    private fun sendRequestSync() {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            timestamp = nowMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbors only
        )
        // Sign and broadcast
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacket(signed)
    }

    private fun sendRequestSyncToPeer(peerID: String) {
        val payload = buildGcsPayload()

        val packet = BitchatPacket(
            type = MessageType.REQUEST_SYNC.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(peerID),
            timestamp = nowMillis().toULong(),
            payload = payload,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS // neighbor only
        )
        PlatformLog.d(TAG, "Sending sync request to $peerID (${payload.size} bytes)")
        // Sign and send directly to peer
        val signed = delegate?.signPacketForBroadcast(packet) ?: packet
        delegate?.sendPacketToPeer(peerID, signed)
    }

    fun handleRequestSync(fromPeerID: String, request: RequestSyncPacket) {
        // Decode GCS into sorted set for membership checks
        val sorted = GCSFilter.decodeToSortedSet(request.p, request.m, request.data)
        fun mightContain(id: ByteArray): Boolean {
            val v = GCSFilter.h64(id) % request.m
            val nonZeroV = if (v == 0L) 1L else v
            return GCSFilter.contains(sorted, nonZeroV)
        }

        // 1) Announcements: send latest per peerID if remote doesn't have them
        for ((_, pair) in latestAnnouncementByPeer.entries) {
            val (id, pkt) = pair
            val idBytes = hexToBytes(id)
            if (!mightContain(idBytes)) {
                // Send original packet unchanged to requester only (keep local TTL)
                val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                delegate?.sendPacketToPeer(fromPeerID, toSend)
                PlatformLog.d(TAG, "Sent sync announce: Type ${toSend.type} from ${toSend.senderID.toHexString()} to $fromPeerID packet id ${idBytes.toHexString()}")
            }
        }

        // 2) Broadcast messages: send all they lack
        val toSendMsgs = messagesLock.withLock { messages.values.toList() }
        for (pkt in toSendMsgs) {
            val idBytes = PacketIdUtil.computeIdBytes(pkt)
            if (!mightContain(idBytes)) {
                val toSend = pkt.copy(ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS)
                delegate?.sendPacketToPeer(fromPeerID, toSend)
                PlatformLog.d(TAG, "Sent sync message: Type ${toSend.type} to $fromPeerID packet id ${idBytes.toHexString()}")
            }
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) result[index] = byte
            tempID = tempID.substring(2)
            index++
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i/2] = clean.substring(i, i+2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun buildGcsPayload(): ByteArray {
        // Collect candidates: latest announcement per peer + recent broadcast messages
        val list = ArrayList<BitchatPacket>()
        // announcements
        for ((_, pair) in latestAnnouncementByPeer) {
            list.add(pair.second)
        }
        // messages
        messagesLock.withLock {
            list.addAll(messages.values)
        }
        // sort by timestamp desc, then take up to min(seenCapacity, fit capacity)
        list.sortByDescending { it.timestamp.toLong() }

        val maxBytes = try { configProvider.gcsMaxBytes() } catch (_: Exception) { defaultMaxBytes }
        val fpr = try { configProvider.gcsTargetFpr() } catch (_: Exception) { defaultFpr }
        val p = GCSFilter.deriveP(fpr)
        val nMax = GCSFilter.estimateMaxElementsForSize(maxBytes, p)
        val cap = configProvider.seenCapacity().coerceAtLeast(1)
        val takeN = minOf(nMax, cap, list.size)
        if (takeN <= 0) {
            val p0 = GCSFilter.deriveP(fpr)
            return RequestSyncPacket(p = p0, m = 1, data = ByteArray(0)).encode()
        }
        val ids = list.take(takeN).map { pkt -> PacketIdUtil.computeIdBytes(pkt) }
        val params = GCSFilter.buildFilter(ids, maxBytes, fpr)
        val mVal = if (params.m <= 0L) 1 else params.m
        return RequestSyncPacket(p = params.p, m = mVal, data = params.data).encode()
    }

    // Periodically remove stale announcements and all their messages
    private fun pruneStaleAnnouncements() {
        val now = nowMillis()
        val stalePeers = mutableListOf<String>()

        // Identify stale announcements by age
        for ((peerID, pair) in latestAnnouncementByPeer.entries) {
            val pkt = pair.second
            val age = now - pkt.timestamp.toLong()
            if (age > com.bitchat.android.util.AppConstants.Mesh.STALE_PEER_TIMEOUT_MS) {
                stalePeers.add(peerID)
            }
        }

        if (stalePeers.isEmpty()) return

        // Remove announcements and their messages
        var totalPrunedMsgs = 0
        for (peerID in stalePeers) {
            // Count messages to be pruned for logging
            val toRemove = mutableListOf<String>()
            messagesLock.withLock {
                for ((id, message) in messages) {
                    val sender = message.senderID.toHexString()
                    if (sender == peerID) toRemove.add(id)
                }
            }
            totalPrunedMsgs += toRemove.size

            // Reuse existing removal which also clears announcement entry
            removeAnnouncementForPeer(peerID)
        }

        PlatformLog.d(TAG, "Pruned ${stalePeers.size} stale announcements and $totalPrunedMsgs messages")
    }

    // Explicitly remove stored announcement for a given peer (hex ID)
    fun removeAnnouncementForPeer(peerID: String) {
        val key = peerID.lowercase()
        if (latestAnnouncementByPeer.remove(key) != null) {
            PlatformLog.d(TAG, "Removed stored announcement for peer $peerID")
        }

        // Collect IDs to remove first to avoid modifying collection while iterating
        val idsToRemove = mutableListOf<String>()
        messagesLock.withLock {
            for ((id, message) in messages) {
                val sender = message.senderID.toHexString()
                if (sender == key) {
                    idsToRemove.add(id)
                }
            }
        }
        
        // Now remove the collected IDs
        messagesLock.withLock {
            for (id in idsToRemove) {
                messages.remove(id)
            }
        }
        
        if (idsToRemove.isNotEmpty()) {
            PlatformLog.d(TAG, "Pruned ${idsToRemove.size} messages with senders without announcements")
        }
    }
}
