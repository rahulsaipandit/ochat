package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.AuthenticatedPeerState

class SecureAuthenticatedPeerStateStore(context: Context) : AuthenticatedPeerStateStore {
    private val identityState = SecureIdentityStateManager(context.applicationContext)

    override fun load(fingerprint: String): AuthenticatedPeerState? =
        identityState.getAuthenticatedPeerState(fingerprint)

    override fun persist(
        fingerprint: String,
        state: AuthenticatedPeerState,
        onCommitted: () -> Unit
    ): Boolean = identityState.storeAuthenticatedPeerState(fingerprint, state, onCommitted)

    override fun isPrivateMediaPinned(fingerprint: String): Boolean =
        identityState.isPrivateMediaCapable(fingerprint)
}
