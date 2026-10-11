package com.bitchat.android.mesh

import com.bitchat.android.protocol.sha256

import com.bitchat.android.protocol.PlatformLog
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.bitchat.android.protocol.ConcurrentMap

/**
 * Shared transport send wrapper that applies bitchat packet fragmentation and
 * transfer progress before a transport writes packets to its concrete medium.
 */
class FragmentingPacketSender(
    private val scope: CoroutineScope,
    private val fragmentManager: FragmentManager?,
    private val logTag: String,
    private val interFragmentDelayMs: Long = 20L
) {
    private val transferJobs = ConcurrentMap<String, Job>()

    fun send(
        routed: RoutedPacket,
        description: String,
        sendSingle: (RoutedPacket) -> Boolean
    ): Boolean {
        val transferId = transferIdFor(routed)
        val packets = packetsForTransport(routed)
        if (packets == null) {
            if (transferId != null) {
                TransferProgressManager.fail(transferId)
            }
            return false
        }
        val total = packets.size

        if (total <= 1) {
            if (transferId != null) {
                TransferProgressManager.start(transferId, 1)
            }
            val sent = sendSingle(
                routed.copy(
                    packet = packets.first(),
                    transferId = transferId,
                    preparedPackets = null
                )
            )
            if (transferId != null) {
                if (sent) {
                    TransferProgressManager.progress(transferId, 1, 1)
                    TransferProgressManager.complete(transferId, 1)
                } else {
                    TransferProgressManager.fail(transferId)
                }
            }
            return sent
        }

        PlatformLog.d(logTag, "Fragmenting packet type ${routed.packet.type} into $total fragments for $description")
        if (transferId != null) {
            TransferProgressManager.start(transferId, total)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            for (packet in packets) {
                if (!isActive) return@launch
                if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch

                val fragment = routed.copy(
                    packet = packet,
                    transferId = transferId,
                    preparedPackets = null
                )
                val delivered = try {
                    sendSingle(fragment)
                } catch (e: Exception) {
                    PlatformLog.e(logTag, "Fragment send failed for $description: ${e.message}")
                    false
                }

                if (!delivered) {
                    PlatformLog.w(logTag, "Stopping fragmented send for $description after $sent/$total fragments")
                    return@launch
                }

                sent += 1
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, sent, total)
                }
                if (sent < total) {
                    delay(interFragmentDelayMs)
                }
            }

            if (transferId != null) {
                TransferProgressManager.complete(transferId, total)
            }
        }

        if (transferId != null) {
            transferJobs[transferId] = job
            job.invokeOnCompletion { transferJobs.remove(transferId, job) }
        }
        job.start()
        return true
    }

    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId) ?: return false
        job.cancel()
        return true
    }

    private fun packetsForTransport(routed: RoutedPacket): List<BitchatPacket>? {
        routed.preparedPackets?.let { prepared ->
            if (prepared.isEmpty() ||
                prepared.size > com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID) {
                PlatformLog.e(logTag, "Rejected invalid prepared fragment plan (${prepared.size} packets)")
                return null
            }
            return prepared
        }

        val packet = routed.packet
        if (packet.type == MessageType.FRAGMENT.value) {
            return listOf(packet)
        }

        val manager = fragmentManager ?: return listOf(packet)
        return try {
            // Receivers hard-cap reassembly at MAX_FRAGMENTS_PER_ID; sending more
            // fragments would be undeliverable, so reject here instead.
            val fragments = manager.createFragments(
                packet,
                com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
            )
            if (fragments.isEmpty()) {
                PlatformLog.e(logTag, "Fragment manager returned no packets for packet type ${packet.type}")
                null
            } else {
                fragments
            }
        } catch (e: Exception) {
            PlatformLog.e(logTag, "Fragment creation failed for packet type ${packet.type}: ${e.message}")
            null
        }
    }

    private fun transferIdFor(routed: RoutedPacket): String? {
        routed.transferId?.let { return it }
        val packet = routed.packet
        return if (packet.type == MessageType.FILE_TRANSFER.value) {
            sha256Hex(packet.payload)
        } else {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        sha256(bytes).toHexString()
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
