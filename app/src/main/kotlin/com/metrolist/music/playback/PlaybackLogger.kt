package com.metrolist.music.playback

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object PlaybackLogger {
    private val buffer = Collections.synchronizedList(mutableListOf<String>())
    private const val MAX_LINES = 500
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] [$tag] $message"
        synchronized(buffer) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeAt(0)
            }
        }
        Timber.tag(tag).i(message)
        Log.i("Zemer_$tag", message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = timeFormat.format(Date())
        val stackTrace = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        val line = "[$timestamp] [$tag] ERROR: $message$stackTrace"
        synchronized(buffer) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeAt(0)
            }
        }
        Timber.tag(tag).e(throwable, message)
        Log.e("Zemer_$tag", message, throwable)
    }

    fun getLog(): String {
        synchronized(buffer) {
            return buffer.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
        }
    }
}
