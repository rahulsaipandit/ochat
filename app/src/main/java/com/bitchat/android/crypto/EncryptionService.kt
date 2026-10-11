package com.bitchat.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.noise.NoiseEncryptionService
import com.bitchat.android.noise.NoiseHandshakeProcessingResult
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

/**
 * Encryption service that now uses NoiseEncryptionService internally
 * Maintains the same public API for backward compatibility
 * 
 * This is the main interface for all encryption/decryption operations in bitchat.
 * It now uses the Noise protocol for secure transport encryption with proper session management.
 */
open class EncryptionService(private val context: Context) : MeshEncryption {
    
    companion object {
        private const val TAG = "EncryptionService"
        private const val ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
        private const val OLD_PREFS_NAME = "bitchat_crypto"
        private const val SECURE_PREFS_NAME = "bitchat_crypto_secure"
    }
    
    // Core Noise encryption service
    private val noiseService: NoiseEncryptionService by lazy { NoiseEncryptionService(SecureIdentityStateManager(context)) { peerID, remoteStaticKey ->
            // Preserve the canonical peerID -> npub index once the Noise handshake authenticated the key.
            FavoritesPersistenceService.shared.findNostrPubkey(remoteStaticKey)?.let { npub ->
                FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(peerID, npub)
            }
        } }
    
    // Session tracking for established connections
    private val establishedSessions = ConcurrentHashMap<String, String>() // peerID -> fingerprint
    
    // Ed25519 signing keys (separate from Noise static keys)
    private lateinit var ed25519PrivateKey: ByteArray // 32-byte RFC 8032 seed
    private lateinit var ed25519PublicKey: ByteArray
    
    // Callbacks for UI state updates
    var onSessionEstablished: ((String) -> Unit)? = null // peerID
    var onSessionLost: ((String) -> Unit)? = null // peerID
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID
    private lateinit var prefs: SharedPreferences
    
    init {
        initialize()
    }

    private fun setUpEncryptedPrefs() {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Create encrypted shared preferences
        prefs = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Initialization logic moved to method to allow overriding in tests
     */
    protected open fun initialize() {
        setUpEncryptedPrefs()
        // Initialize or load Ed25519 signing keys
        val keyPair = loadOrCreateEd25519KeyPair()
        ed25519PrivateKey = keyPair.privateKey
        ed25519PublicKey = keyPair.publicKey
        
        Log.d(TAG, "✅ Ed25519 signing keys initialized")
        
        // Set up NoiseEncryptionService callbacks
        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "✅ Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
        }
        
        noiseService.onHandshakeRequired = { peerID ->
            Log.d(TAG, "🤝 Handshake required for $peerID")
            onHandshakeRequired?.invoke(peerID)
        }
    }
    
    // MARK: - Public API (Maintains backward compatibility)
    
    /**
     * Get our static public key data (32 bytes for Noise)
     * This replaces the old 96-byte combined key format
     */
    override fun getCombinedPublicKeyData(): ByteArray {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Get our static public key for Noise protocol (for identity announcements)
     */
    override fun getStaticPublicKey(): ByteArray? {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Get our signing public key for Ed25519 signatures (for identity announcements)
     */
    override fun getSigningPublicKey(): ByteArray? {
        return ed25519PublicKey.copyOf()
    }
    
    /**
     * Sign data using our Ed25519 signing key (for identity announcements)
     */
    override fun signData(data: ByteArray): ByteArray? {
        return try {
            val signature = Ed25519.sign(ed25519PrivateKey, data)
            Log.d(TAG, "✅ Generated Ed25519 signature (${signature.size} bytes)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to sign data with Ed25519: ${e.message}")
            null
        }
    }
    
    /**
     * Add peer's public key and start handshake if needed
     * For backward compatibility with old key exchange packets
     */
    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        Log.d(TAG, "Legacy addPeerPublicKey called for $peerID with ${publicKeyData.size} bytes")
        
        // If this is from old key exchange format, initiate new Noise handshake
        if (!hasEstablishedSession(peerID)) {
            Log.d(TAG, "No Noise session with $peerID, initiating handshake")
            initiateHandshake(peerID)
        }
    }
    
    /**
     * Get peer's identity key (fingerprint) for favorites
     */
    fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.toByteArray()
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    override fun clearPersistentIdentity() {
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()
        
        // Clear Ed25519 signing key from preferences
        try {
            prefs.edit { remove(ED25519_PRIVATE_KEY_PREF) }
            Log.d(TAG, "🗑️ Cleared Ed25519 signing keys from preferences")

            // Generate new keys immediately
            val keyPair = loadOrCreateEd25519KeyPair()
            val previousPrivateKey = ed25519PrivateKey
            ed25519PrivateKey = keyPair.privateKey
            ed25519PublicKey = keyPair.publicKey
            previousPrivateKey.fill(0) // do not leave the pre-panic signing seed in memory
            Log.d(TAG, "✅ Rotated Ed25519 signing keys in memory")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear Ed25519 keys: ${e.message}")
        }
    }
    
    /**
     * Encrypt data for a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    override fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID")
        }
        return encrypted
    }

    @Throws(Exception::class)
    override fun encryptForSession(
        data: ByteArray,
        peerID: String,
        expectedSession: AuthenticatedNoiseSession
    ): ByteArray = noiseService.encryptForSession(data, peerID, expectedSession)
    
    /**
     * Decrypt data from a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID")
        }
        return decrypted
    }

    @Throws(Exception::class)
    override fun decryptWithSession(data: ByteArray, peerID: String): NoiseDecryptionResult {
        return noiseService.decryptWithSession(data, peerID)
            ?: throw Exception("Failed generation-bound decryption from $peerID")
    }
    
    /**
     * Sign data using our static identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    override fun sign(data: ByteArray): ByteArray {
        // Note: In Noise protocol, authentication is built into the handshake
        // For compatibility, we return empty signature
        return ByteArray(0)
    }
    
    /**
     * Verify signature using peer's identity key
     * Note: This is now done at the packet level, not per-message
     */
    @Throws(Exception::class)
    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean {
        // Note: In Noise protocol, authentication is built into the transport
        // Messages are authenticated automatically when decrypted
        return hasEstablishedSession(peerID)
    }
    
    // MARK: - Noise Protocol Interface
    
    /**
     * Check if we have an established Noise session with a peer
     */
    override fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    override fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState {
        return noiseService.getSessionState(peerID)
    }
    
    /**
     * Get encryption icon state for UI
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }
    
    /**
     * Get peer fingerprint for favorites/blocking
     */
    fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }

    /**
     * Return the remote static key authenticated by the live Noise handshake.
     * This deliberately bypasses announcement and PeerFingerprintManager
     * caches; callers making downgrade decisions must bind to live channel
     * authentication, not a self-certified identity payload.
     */
    fun getAuthenticatedRemoteStaticKey(peerID: String): ByteArray? {
        return getAuthenticatedSession(peerID)?.remoteStaticKey?.copyOf()
    }

    override fun getAuthenticatedSession(peerID: String): AuthenticatedNoiseSession? =
        noiseService.getAuthenticatedSession(peerID)

    override fun withAuthenticatedSession(
        peerID: String,
        expectedSession: AuthenticatedNoiseSession,
        action: () -> Boolean
    ): Boolean = noiseService.withAuthenticatedSession(peerID, expectedSession, action)
    
    /**
     * Get current peer ID for a fingerprint (for peer ID rotation)
     */
    fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }
    
    /**
     * Initiate a Noise handshake with a peer
     */
    override fun initiateHandshake(peerID: String, replaceEstablished: Boolean): ByteArray? {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID, replaceEstablished)
    }
    
    /**
     * Process an incoming handshake message
     */
    override fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }

    /**
     * Process one Noise handshake frame while preserving whether this exact call authenticated a
     * new session. Unlike the response-only compatibility API, binding failures are propagated.
     */
    @Throws(Exception::class)
    override fun processHandshakeMessageWithResult(
        data: ByteArray,
        peerID: String
    ): NoiseHandshakeProcessingResult {
        Log.d(TAG, "🤝 Processing typed handshake message from $peerID")
        return noiseService.processHandshakeMessageWithResult(data, peerID)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    override fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "🗑️ Removed session for $peerID")
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Channel Encryption
    
    /**
     * Set password for a channel (derives encryption key using Argon2id)
     */
    fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }
    
    /**
     * Encrypt message for a password-protected channel
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }
    
    /**
     * Decrypt channel message
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }
    
    /**
     * Remove channel password (when leaving channel)
     */
    fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }
    
    // MARK: - Session Management
    
    /**
     * Get all peers with established sessions
     */
    fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }
    
    /**
     * Get sessions that need rekeying
     */
    fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "🔄 Initiating rekey for $peerID")
        establishedSessions.remove(peerID) // Will be re-added when new session is established
        return noiseService.initiateRekey(peerID)
    }
    
    /**
     * Get our identity fingerprint
     */
    override fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }
    
    /**
     * Get debug information about encryption state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== EncryptionService Debug ===")
        appendLine("Established Sessions: ${establishedSessions.size}")
        appendLine("Our Fingerprint: ${getIdentityFingerprint().take(16)}...")
        
        if (establishedSessions.isNotEmpty()) {
            appendLine("Active Encrypted Sessions:")
            establishedSessions.forEach { (peerID, fingerprint) ->
                appendLine("  $peerID -> ${fingerprint.take(16)}...")
            }
        }
        
        appendLine("")
        appendLine(noiseService.toString()) // Include NoiseService state
    }
    
    /**
     * Shutdown encryption service
     */
    fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "🔌 EncryptionService shut down")
    }
    
    // MARK: - Ed25519 Signature Verification
    
    /**
     * Verify Ed25519 signature against data using a public key
     */
    override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val isValid = Ed25519.verify(signature, data, publicKeyBytes)
            Log.d(TAG, "✅ Ed25519 signature verification: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify Ed25519 signature: ${e.message}")
            false
        }
    }
    
    // MARK: - Private Key Management
    
    /**
     * Load existing Ed25519 key pair from preferences or create a new one
     */
    private fun loadOrCreateEd25519KeyPair(): Ed25519KeyPair {
        // Migrate legacy plaintext Ed25519 key to encrypted storage if present
        migrateOldEd25519KeyIfNeeded()
        try {
            val storedKey = prefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (storedKey != null) {
                // Load existing key
                val privateKeyBytes = Base64.decode(storedKey, Base64.DEFAULT)
                val publicKey = Ed25519.publicKeyFromPrivate(privateKeyBytes)
                Log.d(TAG, "✅ Loaded existing Ed25519 signing key pair")
                return Ed25519KeyPair(privateKeyBytes, publicKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to load existing Ed25519 key, creating new one: ${e.message}")
        }
        
        // Create new key pair
        return generateAndSaveEd25519KeyPair()
    }

    fun generateAndSaveEd25519KeyPair(): Ed25519KeyPair {
        val keyPair = Ed25519.generateKeyPair()

        // Store private key in preferences
        try {
            val encodedKey = Base64.encodeToString(keyPair.privateKey, Base64.DEFAULT)

            prefs.edit { putString(ED25519_PRIVATE_KEY_PREF, encodedKey) }
            Log.d(TAG, "✅ Created and stored new Ed25519 signing key pair")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store Ed25519 private key: ${e.message}")
        }
        
        return keyPair
    }

    private fun migrateOldEd25519KeyIfNeeded() {
        try {
            // old existing plain text preference
            val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)

            val oldKey = oldPrefs.getString(ED25519_PRIVATE_KEY_PREF, null)

            if (oldKey != null && !prefs.contains(ED25519_PRIVATE_KEY_PREF)) {
                prefs.edit {
                    putString(ED25519_PRIVATE_KEY_PREF, oldKey)
                }
                oldPrefs.edit {
                    remove(ED25519_PRIVATE_KEY_PREF)
                }
                Log.d(TAG, "🔁 Migrated Ed25519 key to EncryptedSharedPreferences")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to migrate Ed25519 key; generating new identity: ${e.message}")
        }
    }
}
