package com.bitchat.android.noise

import dev.whyoleg.cryptography.CryptographyProvider

internal actual fun noiseCryptographyProvider(): CryptographyProvider = CryptographyProvider.Default
