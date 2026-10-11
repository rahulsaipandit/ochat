package com.bitchat.android.identity

/**
 * Secure, persistent storage for the device's long-term identity keys. Android backs this with
 * EncryptedSharedPreferences and the Keystore; Apple targets will back it with the Keychain.
 * Keys are (privateKey, publicKey) pairs of 32-byte arrays.
 */
interface IdentityKeyStore {
    /** Noise static key pair, or null if none has been saved. */
    fun loadStaticKey(): Pair<ByteArray, ByteArray>?

    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray)

    /** Ed25519 signing key pair, or null if none has been saved. */
    fun loadSigningKey(): Pair<ByteArray, ByteArray>?

    fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray)

    /** Deletes all identity data (panic wipe). */
    fun clearIdentityData()
}
