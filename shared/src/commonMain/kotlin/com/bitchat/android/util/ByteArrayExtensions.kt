package com.bitchat.android.util

/**
 * Extension function to convert a ByteArray to a lowercase hexadecimal string.
 */
fun ByteArray.toHexString(): String = toHexString(HexFormat.Default)
