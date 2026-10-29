package dev.abode.core.common

import android.util.Log

/**
 * Thin wrapper so the keyboard can log perf counters without pulling a
 * logging framework into the IME process graph.
 */
object AbodeLog {
    private const val TAG = "Abode"

    var enabled: Boolean = true

    fun d(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    fun i(message: String) {
        if (enabled) Log.i(TAG, message)
    }

    fun w(message: String, tr: Throwable? = null) {
        if (enabled) Log.w(TAG, message, tr)
    }
}
