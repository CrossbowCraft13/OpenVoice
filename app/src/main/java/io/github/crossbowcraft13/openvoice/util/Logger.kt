package io.github.crossbowcraft13.openvoice.util

import timber.log.Timber
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Non-blocking logger.
 *
 * The hot paths of the pipeline (router resolution, intent classification,
 * cost-model evaluation, error reporting) must never stall on synchronous
 * logcat IPC — on emulators a single Log.d write can cost ~2–3ms. All log
 * calls are enqueued and written by a dedicated background thread; if the
 * queue is full, new debug/verbose entries are dropped rather than blocking
 * the caller. Pure JVM-safe (no Android Looper) so unit tests can use it too.
 */
object Logger {
    private const val TAG = "OpenVoice"
    private const val THREAD_NAME = "OpenVoice-Logger"
    private const val QUEUE_CAPACITY = 1024

    private enum class Level { VERBOSE, DEBUG, INFO, WARNING, ERROR }

    @Volatile
    private var debugTreePlanted = false

    private data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    private val queue = ArrayBlockingQueue<LogEntry>(QUEUE_CAPACITY)

    private val worker: Thread by lazy {
        Thread({
            while (true) {
                try {
                    val entry = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    val timber = Timber.tag(entry.tag)
                    when (entry.level) {
                        Level.VERBOSE -> timber.v(entry.message)
                        Level.DEBUG -> timber.d(entry.message)
                        Level.INFO -> timber.i(entry.message)
                        Level.WARNING -> timber.w(entry.message)
                        Level.ERROR -> if (entry.throwable != null) {
                            timber.e(entry.throwable, entry.message)
                        } else {
                            timber.e(entry.message)
                        }
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // Logging must never crash the app (or JVM unit tests)
                }
            }
        }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun init(debug: Boolean = true) {
        if (debug && !debugTreePlanted) {
            Timber.plant(Timber.DebugTree())
            debugTreePlanted = true
        }
    }

    fun v(msg: String, tag: String = "Core") = post(Level.VERBOSE, tag, msg)
    fun d(msg: String, tag: String = "Core") = post(Level.DEBUG, tag, msg)
    fun i(msg: String, tag: String = "Core") = post(Level.INFO, tag, msg)
    fun w(msg: String, tag: String = "Core") = post(Level.WARNING, tag, msg)
    fun e(msg: String, tag: String = "Core", t: Throwable? = null) = post(Level.ERROR, tag, msg, t)

    fun perf(label: String, ms: Long) = post(Level.DEBUG, "Perf", "$label: ${ms}ms")

    private fun post(level: Level, tag: String, message: String, t: Throwable? = null) {
        if (!debugTreePlanted) return
        worker // ensure the worker thread is running
        queue.offer(LogEntry(level, "$TAG/$tag", message, t))
    }
}
