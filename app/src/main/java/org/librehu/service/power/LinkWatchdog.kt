package org.librehu.service.power

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.librehu.core.mcu.McuTransport
import org.librehu.core.unit.Watchdog
import org.librehu.service.R
import org.librehu.service.can.CanBoxTalk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the service again when it stops working while the process lives on. 10/11/2026: from v151 to v196 two threads
 * could wait for each other for good while driving (CAN box link, fixed): volume, headlights, steering wheel and touch
 * keys lost without a word until a force stop. Whatever the cause next time, it does not last, and it is said.
 *
 * Watched: the head unit's thread (a beat from its own queue every [BEAT_MS]: a task stuck in it holds the beat back),
 * the main thread (a beat posted to it at each check: the screens, the panels over the apps, the wheel keys' long
 * presses, the reverse camera run there), the MCU reader and writer (a beat at each turn of their loop,
 * [McuTransport.readerBeat]) and, while it runs, the CAN box link's thread ([CanBoxTalk.tickBeat]: without it a Hiworld
 * box gets no car type and sends no key). One of them silent for [Watchdog.STALL_MS] of awake time (CLOCK_MONOTONIC: a
 * standby is not a stall), or an MCU thread ended while the link runs: the stack trace of every thread to logcat (tag
 * LibreHU-Watchdog), those of the watched and the blocked ones to the standby journal, then the process ends and Android
 * starts the service again (START_STICKY, then the Keepalive guard), which says so in a notification. A thread blocked
 * on a lock cannot be interrupted: ending the process is the only way out, as Android's own watchdog does for
 * system_server. At most [Watchdog.MAX_RESTARTS] in [Watchdog.WINDOW_MS]: beyond that, a notification only (force stop).
 */
object LinkWatchdog {
    private const val TAG = "LibreHU-Watchdog"
    private const val BEAT_MS = 5_000L
    private const val CHECK_MS = 5_000L
    private const val MAX_FRAMES = 30
    private const val KEY_RESTARTS = "restarts"
    private const val KEY_REASON = "last_reason"
    private const val KEY_AT = "last_at"
    private const val HEAD_UNIT = "headunit"
    private const val MAIN = "main"
    private const val READER = "mcu-rx"
    private const val WRITER = "mcu-tx"
    private const val CAN_BOX = "can-box"
    private const val CHANNEL = "watchdog"
    private const val NOTIFICATION_RESTARTED = 0x5701
    private const val NOTIFICATION_STUCK = 0x5702

    /** A restart this recent, seen at the next start: written to the journal and notified. */
    private const val BACK_MS = 120_000L

    /** Threads whose stack traces go to the journal, besides the blocked ones (all of them go to logcat). */
    private val WATCHED = listOf(HEAD_UNIT, MAIN, READER, WRITER, CAN_BOX, "can-port")

    private class Armed(
        val transport: McuTransport,
        val executor: ScheduledExecutorService,
        val beat: ScheduledFuture<*>,
    )

    @Volatile
    private var armed: Armed? = null

    /** Last beat of the head unit's thread and of the main thread (ms, CLOCK_MONOTONIC). */
    @Volatile
    private var headUnitBeat = 0L

    @Volatile
    private var mainBeat = 0L

    /** A beat posted to the main thread and not run yet: not posted again (a stuck thread's queue does not grow). */
    private val mainPosted = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

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
        mainBeat = now()
        val beat = executor.scheduleWithFixedDelay({ headUnitBeat = now() }, 0, BEAT_MS, TimeUnit.MILLISECONDS)
        armed = Armed(transport, executor, beat)
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
                if (mainPosted.compareAndSet(false, true)) {
                    main.post {
                        mainBeat = now()
                        mainPosted.set(false)
                    }
                }
                val problem = problem(a) ?: continue
                act(a, problem)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "check: $e")
            }
        }
    }

    /** Each watched thread and its last beat (ms, CLOCK_MONOTONIC). */
    private fun beats(a: Armed): Map<String, Long> =
        buildMap {
            put(HEAD_UNIT, headUnitBeat)
            put(MAIN, mainBeat)
            put(READER, a.transport.readerBeat / 1_000_000)
            put(WRITER, a.transport.writerBeat / 1_000_000)
            if (CanBoxTalk.ticking) put(CAN_BOX, CanBoxTalk.tickBeat / 1_000_000)
        }

    /** What is wrong with the service, or null. */
    private fun problem(a: Armed): String? {
        if (!a.transport.alive) return "an MCU thread has ended"
        val stalls = Watchdog.stalls(beats(a), now())
        if (stalls.isEmpty()) return null
        return stalls.entries.joinToString { (name, ms) -> "$name silent for ${ms / 1000} s" }
    }

    /** For the diagnostics (DiagnosticsProvider): armed or not, the age of each beat, the restarts of this boot. */
    fun status(context: Context): String {
        val a = armed
        val n = now()
        val beats = if (a == null) "not armed (link not running)" else beats(a).entries.joinToString { (k, v) -> "$k ${(n - v) / 1000} s ago" }
        val queue = (a?.executor as? ScheduledThreadPoolExecutor)?.queue?.size
        val p = prefs(context)
        val restarts = Watchdog.recentRestarts(p.getString(KEY_RESTARTS, null), bootId(), SystemClock.elapsedRealtime())
        val last = p.getString(KEY_REASON, null)?.let { "${p.getString(KEY_AT, "?")}: $it" } ?: "none"
        return "beats: $beats; head unit queue: ${queue ?: "?"} tasks; MCU threads alive: ${a?.transport?.alive}; " +
            "restarts in the last ${Watchdog.WINDOW_MS / 60_000} min of this boot: ${restarts.size}; last restart: $last"
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
        Log.e(TAG, "service stuck: $problem")
        for ((t, st) in stacks) Log.e(TAG, "\"${t.name}\" ${t.state}\n" + st.take(MAX_FRAMES).joinToString("\n") { "    at $it" })
        val journal = StandbyJournal.get(c)
        journal.add("Watchdog: service stuck ($problem)")
        for ((t, st) in stacks) {
            if (t.name !in WATCHED && t.state != Thread.State.BLOCKED) continue
            journal.block("Watchdog: thread ${t.name} (${t.state})", st.take(MAX_FRAMES).joinToString("\n") { "at $it" })
        }
        val prefs = prefs(c)
        val boot = bootId()
        val at = SystemClock.elapsedRealtime()
        val saved = prefs.getString(KEY_RESTARTS, null)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        if (!Watchdog.mayRestart(saved, boot, at)) {
            journal.add(
                "Watchdog: ${Watchdog.MAX_RESTARTS} restarts within ${Watchdog.WINDOW_MS / 60_000} min already, not again: " +
                    "force stop the service and send this journal",
            )
            disarm()
            journal.flush()
            notify(
                c,
                NOTIFICATION_STUCK,
                c.getString(R.string.watchdog_stuck_title),
                c.getString(R.string.watchdog_stuck_text, clock, problem, Watchdog.MAX_RESTARTS, Watchdog.WINDOW_MS / 60_000),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)),
            )
            return
        }
        prefs
            .edit()
            .putString(KEY_RESTARTS, Watchdog.withRestart(saved, boot, at))
            .putString(KEY_REASON, problem)
            .putString(KEY_AT, clock)
            .commit()
        journal.add("Watchdog: service ending, Android starts it again")
        // The journal and the service log write on threads of their own: on the storage before the end.
        journal.flush(3_000)
        org.librehu.service.diag.ServiceLog
            .flush(1_000)
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(10)
    }

    /** At the first start of the process: a restart by the watchdog just before, in the journal and a notification. */
    private fun noteRestart(c: Context) {
        val p = prefs(c)
        val last = Watchdog.recentRestarts(p.getString(KEY_RESTARTS, null), bootId(), SystemClock.elapsedRealtime()).lastOrNull() ?: return
        val ago = SystemClock.elapsedRealtime() - last
        if (ago >= BACK_MS) return
        val reason = p.getString(KEY_REASON, null) ?: "?"
        val clock = p.getString(KEY_AT, null) ?: "?"
        Thread({
            StandbyJournal.get(c).add("Watchdog: service back ${ago / 1000} s after its restart")
            notify(c, NOTIFICATION_RESTARTED, c.getString(R.string.watchdog_restarted_title), c.getString(R.string.watchdog_restarted_text, clock, reason), null)
        }, "watchdog-note").start()
    }

    private fun notify(
        c: Context,
        id: Int,
        title: String,
        text: String,
        open: Intent?,
    ) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, c.getString(R.string.watchdog_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            val b =
                Notification
                    .Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_headunit)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
            if (open != null) {
                b.setContentIntent(
                    PendingIntent.getActivity(c, id, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            nm.notify(id, b.build())
        } catch (e: Exception) {
            Log.w(TAG, "notification: $e")
        }
    }

    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("watchdog", Context.MODE_PRIVATE)

    private fun bootId(): String = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull() ?: "?"
}
