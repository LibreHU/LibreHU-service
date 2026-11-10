package org.librehu.core.unit

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory

/**
 * One thread, as `Executors.newSingleThreadScheduledExecutor`, whose task errors are logged instead of lost: a
 * ScheduledThreadPoolExecutor keeps the exception of a task in its Future, which nobody reads, so an MCU frame whose
 * handling throws left no trace (and a periodic task stopped for good). [log] gets "task error: <error> at <where>";
 * the same error twice in a row is logged once, [MAX_LOGGED] errors at most, then their count every 100.
 */
class LoggedExecutor(
    name: String,
    private val log: (String) -> Unit,
) : ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, name) }) {
    /** Task errors since the start. */
    @Volatile
    var errors = 0
        private set

    /** Only the executor's thread reads and writes it (afterExecute). */
    private var last: String? = null

    override fun afterExecute(
        r: Runnable?,
        t: Throwable?,
    ) {
        super.afterExecute(r, t)
        var e = t
        // A periodic task that ran well is not done: get() would wait for its next run.
        if (e == null && r is Future<*> && r.isDone) {
            try {
                r.get()
            } catch (_: CancellationException) {
            } catch (x: ExecutionException) {
                e = x.cause ?: x
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (e == null) return
        errors++
        val m = "task error: $e at ${e.stackTrace.take(3).joinToString(" < ")}"
        val again = m == last
        last = m
        try {
            when {
                errors <= MAX_LOGGED && !again -> log(m)
                errors % 100 == 0 -> log("task errors: $errors so far, the last: $m")
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_LOGGED = 100
    }
}
