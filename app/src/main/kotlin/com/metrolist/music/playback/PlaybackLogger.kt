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

    /**
     * Write directly to the buffer. Called by [PlaybackLogTree] for every Timber log.
     * Does NOT call Timber (to avoid infinite loop).
     */
    internal fun appendToBuffer(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        val timestamp = timeFormat.format(Date())
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val stackTrace = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        val line = "[$timestamp] [$level] [${tag ?: "?"}] $message$stackTrace"
        synchronized(buffer) {
            buffer.add(line)
            while (buffer.size > MAX_LINES) {
                buffer.removeAt(0)
            }
        }
    }

    fun log(tag: String, message: String) {
        appendToBuffer(Log.INFO, tag, message, null)
        Log.i("Zemer_$tag", message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        appendToBuffer(Log.ERROR, tag, "ERROR: $message", throwable)
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

    /**
     * Timber Tree that logs to Logcat (like DebugTree) AND captures everything
     * into PlaybackLogger's in-memory buffer for the in-app log dialog.
     */
    class PlaybackLogTree : Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            super.log(priority, tag, message, t)
            appendToBuffer(priority, tag, message, t)
        }
    }
}
