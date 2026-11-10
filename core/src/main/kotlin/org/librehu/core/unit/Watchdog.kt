package org.librehu.core.unit

/**
 * Pure logic of the service's watchdog (LinkWatchdog in the app): which watched threads stopped beating, and whether
 * one more restart is allowed. Beats in ms of a clock that stops while the SoC is suspended (CLOCK_MONOTONIC:
 * `System.nanoTime()`, Android's `SystemClock.uptimeMillis()`), so a standby is never taken for a stall; restarts in
 * ms since boot (`SystemClock.elapsedRealtime()`). As Android's own watchdog of system_server: a thread stuck for
 * 60 s, then every thread's stack trace and the process ended.
 */
object Watchdog {
    /** A watched thread silent this long is stuck. */
    const val STALL_MS = 60_000L

    /** Restarts allowed within [WINDOW_MS] of the same boot; beyond that a stall is only reported. */
    const val MAX_RESTARTS = 3
    const val WINDOW_MS = 10 * 60_000L

    /** The watched threads ([beats]: name → time of the last beat) silent for more than [stallMs] at [now], and for how long. */
    fun stalls(
        beats: Map<String, Long>,
        now: Long,
        stallMs: Long = STALL_MS,
    ): Map<String, Long> = beats.mapValues { now - it.value }.filterValues { it > stallMs }

    /**
     * The restarts kept in [saved] (one "boot time" per line, time in ms since boot) that still count at [now] on
     * [boot]: same boot, within [WINDOW_MS]. Lines of another boot or unreadable ones are dropped.
     */
    fun recentRestarts(
        saved: String?,
        boot: String,
        now: Long,
    ): List<Long> =
        saved.orEmpty().lines().mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size != 2 || parts[0] != boot) return@mapNotNull null
            parts[1].toLongOrNull()?.takeIf { it <= now && now - it < WINDOW_MS }
        }

    fun mayRestart(
        saved: String?,
        boot: String,
        now: Long,
    ): Boolean = recentRestarts(saved, boot, now).size < MAX_RESTARTS

    /** [saved] with a restart at [now] added, the ones that no longer count dropped. */
    fun withRestart(
        saved: String?,
        boot: String,
        now: Long,
    ): String = (recentRestarts(saved, boot, now) + now).joinToString("\n") { "$boot $it" }
}
