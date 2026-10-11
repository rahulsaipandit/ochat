package com.bitchat.android.protocol

import kotlin.math.ceil

/**
 * Fair, weighted admission control for decompression allocations.
 *
 * Permits represent memory rather than workers: small packets can proceed concurrently while
 * near-limit packets consume most of the budget. Callers must reserve before allocating any
 * packet-specific compressed copy or expanded output.
 */
class DecompressionResourcePool(
    budgetBytes: Long,
    private val unitBytes: Int,
    private val waitTimeoutMs: Long
) {
    private val totalPermits = (budgetBytes / unitBytes).toInt().coerceAtLeast(1)
    private val permits = PermitGate(totalPermits)

    fun <T> withReservation(bytes: Long, block: () -> T): T? {
        val requiredPermits = permitsFor(bytes)
        val acquired = permits.tryAcquire(requiredPermits, waitTimeoutMs)
        if (!acquired) return null

        return try {
            block()
        } finally {
            permits.release(requiredPermits)
        }
    }

    fun permitsFor(bytes: Long): Int =
        ceil(bytes.coerceAtLeast(1).toDouble() / unitBytes.toDouble())
            .toInt()
            .coerceAtMost(totalPermits)

    @InternalTestApi
    val availablePermits: Int
        get() = permits.available

    companion object {
        private const val DEFAULT_UNIT_BYTES = 256 * 1024
        private const val DEFAULT_WAIT_TIMEOUT_MS = 1_000L
        private const val HEAP_BUDGET_DIVISOR = 8L
        private const val MAX_BUDGET_BYTES = 64L * 1024 * 1024

        fun forRuntime(
            maxHeapBytes: Long = maxHeapBytes(),
            maxPacketResourceBytes: Long =
                2L * ProtocolConstants.MAX_PAYLOAD_LENGTH
        ): DecompressionResourcePool {
            val budget = recommendedBudgetBytes(maxHeapBytes, maxPacketResourceBytes)
            return DecompressionResourcePool(
                budgetBytes = budget,
                unitBytes = DEFAULT_UNIT_BYTES,
                waitTimeoutMs = DEFAULT_WAIT_TIMEOUT_MS
            )
        }

        fun recommendedBudgetBytes(
            maxHeapBytes: Long,
            maxPacketResourceBytes: Long
        ): Long = (maxHeapBytes / HEAP_BUDGET_DIVISOR)
            .coerceAtLeast(maxPacketResourceBytes)
            .coerceAtMost(MAX_BUDGET_BYTES.coerceAtLeast(maxPacketResourceBytes))
    }
}
