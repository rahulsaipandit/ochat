package com.bitchat.android.model

import kotlin.time.Clock

actual class Date {
    private val millis: Long

    actual constructor(time: Long) {
        millis = time
    }

    actual constructor() : this(Clock.System.now().toEpochMilliseconds())

    actual fun getTime(): Long = millis

    override fun equals(other: Any?): Boolean = other is Date && other.millis == millis

    override fun hashCode(): Int = millis.hashCode()

    override fun toString(): String = "Date($millis)"
}
