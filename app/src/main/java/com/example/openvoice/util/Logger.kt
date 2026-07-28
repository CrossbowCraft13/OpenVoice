package com.example.openvoice.util

import timber.log.Timber

object Logger {
    private const val TAG = "OpenVoice"

    fun init(debug: Boolean = true) {
        if (debug) Timber.plant(Timber.DebugTree())
    }

    fun v(msg: String, tag: String = "Core") = Timber.tag("$TAG/$tag").v(msg)
    fun d(msg: String, tag: String = "Core") = Timber.tag("$TAG/$tag").d(msg)
    fun i(msg: String, tag: String = "Core") = Timber.tag("$TAG/$tag").i(msg)
    fun w(msg: String, tag: String = "Core") = Timber.tag("$TAG/$tag").w(msg)
    fun e(msg: String, tag: String = "Core", t: Throwable? = null) = Timber.tag("$TAG/$tag").e(t, msg)

    fun perf(label: String, ms: Long) = Timber.tag("$TAG/Perf").d("$label: ${ms}ms")
}
