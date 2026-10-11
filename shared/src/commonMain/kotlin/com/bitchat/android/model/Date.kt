package com.bitchat.android.model

/** Wall-clock instant. java.util.Date on Android, so existing app code is unaffected. */
expect class Date {
    constructor()
    constructor(time: Long)

    fun getTime(): Long
}
