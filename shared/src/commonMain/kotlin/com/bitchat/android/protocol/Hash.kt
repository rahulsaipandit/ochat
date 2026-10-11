package com.bitchat.android.protocol

import com.bitchat.android.noise.DefaultNoiseCrypto

/** SHA-256 digest of [data]. */
fun sha256(data: ByteArray): ByteArray = DefaultNoiseCrypto.sha256(data)
