package com.bitchat.android.mesh

import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.withLock

import com.bitchat.android.protocol.nowMillis

import com.bitchat.android.protocol.PlatformLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bitchat.android.protocol.ConcurrentMap

/**
 * Abstract base tracker for mesh connections (BLE, Wi-Fi Aware, etc.)
 * Encapsulates common state machine logic:
 * - Connection attempt tracking (retries, backoff)
 * - Pending connection management
 * - Automatic cleanup of expired attempts
 */
abstract class MeshConnectionTracker(
    private val scope: CoroutineScope,
    protected val tag: String
) {
    companion object {
        const val CONNECTION_RETRY_DELAY = 5_000L
        const val MAX_CONNECTION_ATTEMPTS = 3
        const val CLEANUP_INTERVAL = 30_000L
    }

    /**
     * Connection attempt tracking with automatic expiry
     */
    protected data class ConnectionAttempt(
        val attempts: Int,
        val lastAttempt: Long = nowMillis()
    ) {
        fun isExpired(): Boolean =
            nowMillis() - lastAttempt > CONNECTION_RETRY_DELAY * 2

        fun shouldRetry(): Boolean =
            attempts < MAX_CONNECTION_ATTEMPTS &&
                    nowMillis() - lastAttempt > CONNECTION_RETRY_DELAY
    }

    // Tracks in-progress or failed attempts
    protected val pendingConnections = ConcurrentMap<String, ConnectionAttempt>()
    private val pendingConnectionsLock = PlatformLock()

    private var isActive = false

    /**
     * Start the tracker and its cleanup loop
     */
    open fun start() {
        isActive = true
        startPeriodicCleanup()
    }

    /**
     * Stop the tracker
     */
    open fun stop() {
        isActive = false
        pendingConnections.clear()
    }

    /**
     * Check if a connection attempt is allowed for this peer/address
     */
    fun isConnectionAttemptAllowed(id: String): Boolean {
        // If already connected, usually no need to retry (subclasses can override logic if needed,
        // but typically the caller checks isConnected() first).
        
        val existingAttempt = pendingConnections[id]
        return existingAttempt?.let {
            it.isExpired() || it.shouldRetry()
        } ?: true
    }

    /**
     * Record a new connection attempt.
     * Returns true if the attempt was recorded (allowed), false if skipped.
     */
    fun addPendingConnection(id: String): Boolean {
        pendingConnectionsLock.withLock {
            val currentAttempt = pendingConnections[id]
            
            // If strictly not allowed right now, reject
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                PlatformLog.d(tag, "Connection attempt already in progress for $id")
                return false
            }

            // Update attempt count
            // Reset to 1 if expired, otherwise increment
            val attempts = if (currentAttempt?.isExpired() == true) 1 else (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[id] = ConnectionAttempt(attempts)
            PlatformLog.d(tag, "Added pending connection for $id (attempts: $attempts)")
            return true
        }
    }

    /**
     * Remove a pending attempt (e.g., on success or fatal error)
     */
    fun removePendingConnection(id: String) {
        pendingConnections.remove(id)
    }

    /**
     * Abstract: Subclasses must define what "connected" means
     */
    abstract fun isConnected(id: String): Boolean

    /**
     * Abstract: Subclasses must implement disconnect logic
     */
    abstract fun disconnect(id: String)

    /**
     * Abstract: Subclasses report their active connection count
     */
    abstract fun getConnectionCount(): Int

    private fun startPeriodicCleanup() {
        scope.launch {
            while (isActive) {
                try {
                    delay(CLEANUP_INTERVAL)
                    if (!isActive) break

                    // Clean up expired pending connections
                    val expired = pendingConnections.filter { it.value.isExpired() }
                    expired.keys.forEach { pendingConnections.remove(it) }

                    if (expired.isNotEmpty()) {
                        PlatformLog.d(tag, "Cleaned up ${expired.size} expired connection attempts")
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    PlatformLog.w(tag, "Error in periodic cleanup: ${e.message}")
                }
            }
        }
    }
}
