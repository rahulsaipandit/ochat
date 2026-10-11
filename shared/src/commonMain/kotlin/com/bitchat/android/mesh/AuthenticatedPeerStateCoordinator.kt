package com.bitchat.android.mesh

import com.bitchat.android.protocol.sha256
import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.withLock

import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.protocol.ConcurrentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface AuthenticatedPeerStateStore {
    fun load(fingerprint: String): AuthenticatedPeerState?
    fun persist(
        fingerprint: String,
        state: AuthenticatedPeerState,
        onCommitted: () -> Unit
    ): Boolean
    fun isPrivateMediaPinned(fingerprint: String): Boolean
}

sealed interface AuthenticatedPeerStateStatus {
    data object Missing : AuthenticatedPeerStateStatus
    data object Awaiting : AuthenticatedPeerStateStatus
    data object TimedOut : AuthenticatedPeerStateStatus
    data class Proven(val state: AuthenticatedPeerState) : AuthenticatedPeerStateStatus
}

/** Fresh, generation-scoped authenticated peer-state exchange for Noise payload 0x21. */
class AuthenticatedPeerStateCoordinator(
    private val scope: CoroutineScope,
    private val authenticatedSessionProvider: (String) -> AuthenticatedNoiseSession?,
    private val withAuthenticatedSession: (
        String,
        AuthenticatedNoiseSession,
        () -> Boolean
    ) -> Boolean,
    private val store: AuthenticatedPeerStateStore,
    private val localStateProvider: () -> AuthenticatedPeerState,
    private val applyAuthenticatedState: (String, ByteArray, AuthenticatedPeerState) -> Unit,
    private val sendState: (String, AuthenticatedPeerState, AuthenticatedNoiseSession) -> Boolean,
    private val onResolution: (String) -> Unit,
    private val proofTimeoutMs: Long = 5_000L
) {
    private data class SessionState(
        val authenticatedSession: AuthenticatedNoiseSession,
        val fingerprint: String,
        var status: AuthenticatedPeerStateStatus,
        var echoSent: Boolean,
        var timeoutJob: Job? = null
    )

    private val lock = PlatformLock()
    private val sessions = ConcurrentMap<String, SessionState>()

    fun onSessionAuthenticated(
        peerID: String,
        authenticatedRemoteStatic: ByteArray,
        authenticatedSessionToken: ByteArray
    ) {
        if (!NoisePeerIdentity.matchesClaimedPeerID(peerID, authenticatedRemoteStatic)) return
        if (authenticatedSessionToken.size != 32 ||
            authenticatedSessionToken.all { it == 0.toByte() }
        ) return
        val authenticatedSession = AuthenticatedNoiseSession(
            authenticatedRemoteStatic.copyOf(),
            authenticatedSessionToken.copyOf()
        )
        ensureSession(peerID, authenticatedSession)
    }

    /** Install one watchdog/exchange for this exact live generation, without resetting it. */
    private fun ensureSession(
        peerID: String,
        authenticatedSession: AuthenticatedNoiseSession
    ): SessionState? {
        val authenticatedRemoteStatic = authenticatedSession.remoteStaticKey
        if (!NoisePeerIdentity.matchesClaimedPeerID(peerID, authenticatedRemoteStatic)) return null
        if (authenticatedSession.sessionToken.size != 32 ||
            authenticatedSession.sessionToken.all { it == 0.toByte() }
        ) return null
        // Ignore a delayed callback or policy snapshot if a later generation is already active.
        if (authenticatedSessionProvider(peerID) != authenticatedSession) return null
        val session = SessionState(
            authenticatedSession = authenticatedSession,
            fingerprint = fingerprint(authenticatedRemoteStatic),
            status = AuthenticatedPeerStateStatus.Awaiting,
            echoSent = false
        )
        val installed = withAuthenticatedSession(peerID, authenticatedSession) {
            lock.withLock {
                val existing = sessions[peerID]
                if (existing?.authenticatedSession == authenticatedSession) {
                    return@withLock false
                }
                sessions.put(peerID, session)?.timeoutJob?.cancel()
                true
            }
        }
        if (!installed) return lock.withLock { sessions[peerID] }

        // Emit for every authenticated generation/rekey. Failure does not relax the watchdog.
        runCatching { sendState(peerID, localStateProvider(), authenticatedSession) }

        val timeout = scope.launch {
            delay(proofTimeoutMs)
            val resolved = lock.withLock {
                val current = sessions[peerID]
                if (current !== session || current.status !is AuthenticatedPeerStateStatus.Awaiting) {
                    false
                } else {
                    current.status = AuthenticatedPeerStateStatus.TimedOut
                    true
                }
            }
            if (resolved) onResolution(peerID)
        }
        lock.withLock {
            if (sessions[peerID] === session && session.status is AuthenticatedPeerStateStatus.Awaiting) {
                session.timeoutJob = timeout
            } else {
                timeout.cancel()
            }
        }
        return session
    }

    /** Accept the first valid proof for this generation; repeated equal proofs are idempotent. */
    fun receive(
        peerID: String,
        state: AuthenticatedPeerState,
        decryptedSession: AuthenticatedNoiseSession
    ): Boolean {
        val remoteStatic = decryptedSession.remoteStaticKey
        if (!NoisePeerIdentity.matchesClaimedPeerID(peerID, remoteStatic)) return false
        if (decryptedSession.sessionToken.size != 32 ||
            decryptedSession.sessionToken.all { it == 0.toByte() }
        ) return false
        val currentFingerprint = fingerprint(remoteStatic)

        var shouldEcho = false
        var echoSession: AuthenticatedNoiseSession? = null
        val accepted = withAuthenticatedSession(peerID, decryptedSession) {
            lock.withLock {
                val current = sessions[peerID] ?: return@withLock false
                if (current.fingerprint != currentFingerprint ||
                    current.authenticatedSession != decryptedSession
                ) return@withLock false
                val proven = current.status as? AuthenticatedPeerStateStatus.Proven
                if (proven != null) return@withLock proven.state == state
                try {
                    // Persist before publishing the replacement Ed key in memory, so a restart
                    // cannot reopen copied-static first-announce poisoning. The Noise manager
                    // lease prevents this generation from being replaced during the transition.
                    if (!store.persist(currentFingerprint, state) {
                            // Publish while the persistence epoch lock is still held. A panic wipe
                            // can therefore happen before both operations or after both, never between.
                            applyAuthenticatedState(peerID, remoteStatic, state)
                        }
                    ) return@withLock false
                    current.timeoutJob?.cancel()
                    current.status = AuthenticatedPeerStateStatus.Proven(state)
                    if (!current.echoSent) {
                        current.echoSent = true
                        shouldEcho = true
                        echoSession = current.authenticatedSession
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        if (!accepted) return false
        if (shouldEcho) {
            val exactSession = echoSession ?: return false
            runCatching { sendState(peerID, localStateProvider(), exactSession) }
        }
        onResolution(peerID)
        return true
    }

    fun status(
        peerID: String,
        authenticatedSession: AuthenticatedNoiseSession
    ): AuthenticatedPeerStateStatus {
        ensureSession(peerID, authenticatedSession)
        val currentFingerprint = fingerprint(authenticatedSession.remoteStaticKey)
        return lock.withLock {
            sessions[peerID]?.takeIf {
                it.fingerprint == currentFingerprint &&
                    it.authenticatedSession == authenticatedSession
            }?.status
                ?: AuthenticatedPeerStateStatus.Missing
        }
    }

    fun persistedSigningKeyFor(noisePublicKey: ByteArray): ByteArray? {
        if (noisePublicKey.size != 32) return null
        return store.load(fingerprint(noisePublicKey))?.signingPublicKey?.copyOf()
    }

    fun isPrivateMediaPinned(peerID: String): Boolean {
        val authenticatedSession = authenticatedSessionProvider(peerID) ?: return false
        return store.isPrivateMediaPinned(fingerprint(authenticatedSession.remoteStaticKey))
    }

    fun clear(peerID: String) {
        lock.withLock { sessions.remove(peerID)?.timeoutJob?.cancel() }
    }

    private fun fingerprint(publicKey: ByteArray): String =
        sha256(publicKey).toHexString()
}
