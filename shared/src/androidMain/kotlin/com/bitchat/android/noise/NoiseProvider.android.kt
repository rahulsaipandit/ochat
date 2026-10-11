package com.bitchat.android.noise

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

// Constructing BouncyCastleProvider registers its whole algorithm table, so build it once.
private val provider: CryptographyProvider by lazy { CryptographyProvider.JDK(BouncyCastleProvider()) }

internal actual fun noiseCryptographyProvider(): CryptographyProvider = provider
