package org.librehu.service.power

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.librehu.core.mcu.McuTransport
import org.librehu.core.unit.Watchdog
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Starts the service again when its MCU link stops working while the process lives on. 10/11/2026: from v151 to v196
 * two threads could wait for each other for good while driving (CAN box link, fixed): volume, headlights, steering
 * wheel and touch keys lost without a word until a force stop. Whatever the cause next time, it does not last.
 *
 * Watched: the head unit's thread (a beat from its own queue every [BEAT_MS]: a task stuck in it holds the beat back)
 * and the MCU reader and writer (a beat at each turn of their loop, [McuTransport.readerBeat]). One of them silent for
 * [Watchdog.STALL_MS] of awake time (CLOCK_MONOTONIC: a standby is not a stall), or an MCU thread ended while the link
 * runs: the stack trace of every thread to logcat (tag LibreHU-Watchdog), those of the watched and the blocked ones to
 * the standby journal, then the process ends and Android starts the service again (START_STICKY, then the Keepalive
 * guard). A thread blocked on a lock cannot be interrupted: ending the process is the only way out, as Android's own
 * watchdog does for system_server. At most [Watchdog.MAX_RESTARTS] in [Watchdog.WINDOW_MS]: beyond that, reported only.
 */
object LinkWatchdog {
    private const val TAG = "LibreHU-Watchdog"
    private const val BEAT_MS = 5_000L
    private const val CHECK_MS = 5_000L
    private const val MAX_FRAMES = 30
    private const val KEY_RESTARTS = "restarts"
    private const val HEAD_UNIT = "headunit"
    private const val READER = "mcu-rx"
    private const val WRITER = "mcu-tx"

    /** A restart this recent, seen at the next start: written to the journal. */
    private const val BACK_MS = 120_000L

    /** Threads whose stack traces go to the journal, besides the blocked ones (all of them go to logcat). */
    private val WATCHED = listOf(HEAD_UNIT, READER, WRITER, "can-box", "can-port", "main")

    private class Armed(
        val transport: McuTransport,
        val beat: ScheduledFuture<*>,
    )

    @Volatile
    private var armed: Armed? = null

    /** Last beat of the head unit's thread (ms, CLOCK_MONOTONIC). */
    @Volatile
    private var headUnitBeat = 0L

    private var app: Context? = null
    private var checker: Thread? = null

    /** Watches the link just started: [executor] is the head unit's, [transport] the MCU's. */
    @Synchronized
    fun arm(
        context: Context,
        executor: ScheduledExecutorService,
        transport: McuTransport,
    ) {
        disarm()
        if (app == null) {
            app = context.applicationContext
            noteRestart(context.applicationContext)
        }
        headUnitBeat = now()
        val beat = executor.scheduleWithFixedDelay({ headUnitBeat = now() }, 0, BEAT_MS, TimeUnit.MILLISECONDS)
        armed = Armed(transport, beat)
        if (checker == null) {
            checker =
                Thread(::loop, "link-watchdog").apply {
                    isDaemon = true
                    start()
                }
        }
    }

    /** Before the link stops: its threads end, they are not stuck. */
    @Synchronized
    fun disarm() {
        armed?.beat?.cancel(false)
        armed = null
    }

    /** CLOCK_MONOTONIC in ms, as SystemClock.uptimeMillis() and McuTransport's beats: stopped while the SoC sleeps. */
    private fun now() = System.nanoTime() / 1_000_000

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(CHECK_MS)
                val a = armed ?: continue
                val problem = problem(a) ?: continue
                act(a, problem)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "check: $e")
            }
        }
    }

    /** What is wrong with the link, or null. */
    private fun problem(a: Armed): String? {
        val t = a.transport
        if (!t.alive) return "an MCU thread has ended"
        val beats = mapOf(HEAD_UNIT to headUnitBeat, READER to t.readerBeat / 1_000_000, WRITER to t.writerBeat / 1_000_000)
        val stalls = Watchdog.stalls(beats, now())
        if (stalls.isEmpty()) return null
        return stalls.entries.joinToString { (name, ms) -> "$name silent for ${ms / 1000} s" }
    }

    @Synchronized
    private fun act(
        a: Armed,
        problem: String,
    ) {
        // Stopped meanwhile (the link restarted from the screen, the service stopping): nothing stuck.
        if (armed !== a) return
        // A debugger holding threads at a breakpoint (Android's watchdog lets it be too).
        if (android.os.Debug.isDebuggerConnected()) return
        val c = app ?: return
        val stacks = Thread.getAllStackTraces().entries.sortedBy { (t, _) -> WATCHED.indexOf(t.name).let { if (it < 0) WATCHED.size else it } }
        Log.e(TAG, "MCU link stuck: $problem")
        for ((t, st) in stacks) Log.e(TAG, "\"${t.name}\" ${t.state}\n" + st.take(MAX_FRAMES).joinToString("\n") { "    at $it" })
        val journal = StandbyJournal.get(c)
        journal.add("Watchdog: MCU link stuck ($problem)")
        for ((t, st) in stacks) {
            if (t.name !in WATCHED && t.state != Thread.State.BLOCKED) continue
            journal.block("Watchdog: thread ${t.name} (${t.state})", st.take(MAX_FRAMES).joinToString("\n") { "at $it" })
        }
        val prefs = prefs(c)
        val boot = bootId()
        val at = SystemClock.elapsedRealtime()
        val saved = prefs.getString(KEY_RESTARTS, null)
        if (!Watchdog.mayRestart(saved, boot, at)) {
            journal.add(
                "Watchdog: ${Watchdog.MAX_RESTARTS} restarts within ${Watchdog.WINDOW_MS / 60_000} min already, not again: " +
                    "force stop the service and send this journal",
            )
            disarm()
            return
        }
        prefs.edit().putString(KEY_RESTARTS, Watchdog.withRestart(saved, boot, at)).commit()
        journal.add("Watchdog: service ending, Android starts it again")
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(10)
    }

    /** At the first start of the process: a restart by the watchdog just before, written to the journal. */
    private fun noteRestart(c: Context) {
        val last = Watchdog.recentRestarts(prefs(c).getString(KEY_RESTARTS, null), bootId(), SystemClock.elapsedRealtime()).lastOrNull() ?: return
        val ago = SystemClock.elapsedRealtime() - last
        if (ago < BACK_MS) Thread({ StandbyJournal.get(c).add("Watchdog: service back ${ago / 1000} s after its restart") }, "watchdog-note").start()
    }

    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("watchdog", Context.MODE_PRIVATE)

    private fun bootId(): String = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull() ?: "?"
}
