package com.bitchat.android.protocol

/**
 * Marks hooks that exist only so tests (which live in other modules) can reach them. Production
 * code must not use them; tests opt in with @OptIn(InternalTestApi::class).
 */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Test-only API; do not use in production code")
@Retention(AnnotationRetention.BINARY)
annotation class InternalTestApi
