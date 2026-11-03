package org.librehu.service.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.librehu.core.bridge.BridgeChannel
import org.librehu.core.bridge.BridgeException
import org.librehu.service.R
import org.librehu.service.root.RootShell
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The head unit's screen on a phone of the bridge (LibreHU phone app, over Wi-Fi), which touches it from there: for the
 * passenger. [ScreenHelper], run as root, encodes the screen in H.264; each packet goes to the phone in a binary frame
 * of its encrypted channel (`kind (1 byte) pts (8, µs) data`), the phone's touches and keys come back as
 * `screen.touch` / `screen.key` requests. One phone at a time: a new one takes it over. While it is shared, a
 * notification on the head unit says so and stops it.
 */
class ScreenShare private constructor(
    context: Context,
) {
    private val app = context.applicationContext

    private inner class Session(
        val ch: BridgeChannel,
        val phone: String,
        val process: Process,
    ) {
        val stdin: OutputStream = process.outputStream
        val ready = CompletableFuture<JSONObject>()

        /** Packets waiting to be sent: the phone gets the latest screen even when the Wi-Fi slows down. */
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE)

        @Volatile
        var open = true

        /** Last lines of the helper's errors (the reason when it stops). */
        val errors = ArrayDeque<String>()
        var sender: Thread? = null
    }

    @Volatile
    private var session: Session? = null

    /**
     * Shares the screen with the phone [phone] on [ch], in a video at most [max] pixels wide and of [bitrate] bit/s;
     * answers `w`, `h` (video) and `sw`, `sh` (screen) once the helper runs.
     */
    fun start(
        ch: BridgeChannel,
        phone: String,
        max: Int,
        bitrate: Int,
    ): JSONObject {
        if (!RootShell.isAvailable()) throw BridgeException(app.getString(R.string.screen_no_root))
        val taken = app.getString(R.string.screen_taken, phone)
        // The phone sharing now hands over; its helper first frees the encoder.
        session?.let { old ->
            // The same phone starting again (retry) is told nothing.
            end(old, taken.takeIf { old.ch !== ch })
            runCatching { old.process.waitFor(STOP_MS, TimeUnit.MILLISECONDS) }
        }
        val apk = app.applicationInfo.sourceDir
        val p =
            RootShell.spawn("CLASSPATH='$apk' exec app_process /system/bin ${ScreenHelper::class.java.name} $max $bitrate")
                ?: throw BridgeException(app.getString(R.string.screen_no_root))
        val s = Session(ch, phone, p)
        synchronized(this) { session.also { session = s } }?.let { prev -> end(prev, taken.takeIf { prev.ch !== ch }) }
        thread("screen-err") {
            p.errorStream.bufferedReader().forEachLine { line ->
                Log.w(TAG, "helper: $line")
                synchronized(s.errors) {
                    s.errors.addLast(line)
                    if (s.errors.size > 4) s.errors.removeFirst()
                }
            }
        }
        thread("screen-read") { read(s) }
        s.sender = thread("screen-send") { send(s) }
        val info =
            try {
                s.ready.get(START_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                val why = reason(s) ?: (e.cause ?: e).message ?: "timeout"
                end(s, null)
                throw BridgeException(why)
            }
        notify(phone)
        Log.i(TAG, "screen shared with $phone: $info")
        return info
    }

    /** Stops the share of [ch] (the phone asked, or its link closed). */
    fun stop(ch: BridgeChannel) {
        session?.takeIf { it.ch === ch }?.let { end(it, null) }
    }

    /** Stops any share (the notification's key). */
    fun stopAll() {
        session?.let { end(it, app.getString(R.string.screen_stopped_here)) }
    }

    /** A line for the helper ([ScreenHelper]'s stdin), from the phone sharing on [ch] only. */
    fun input(
        ch: BridgeChannel,
        line: String,
    ) {
        val s = session?.takeIf { it.ch === ch && it.open } ?: return
        try {
            synchronized(s.stdin) {
                s.stdin.write((line + "\n").toByteArray())
                s.stdin.flush()
            }
        } catch (e: IOException) {
            end(s, reason(s))
        }
    }

    private fun read(s: Session) {
        try {
            val din = DataInputStream(BufferedInputStream(s.process.inputStream, 1 shl 16))
            var dropping = false
            while (s.open) {
                val kind = din.readUnsignedByte()
                val n = din.readInt()
                if (n < 0 || n > MAX_PACKET) throw IOException("packet of $n bytes")
                val pts = din.readLong()
                // Binary frame for the phone: kind, pts, data.
                val payload = ByteArray(9 + n)
                payload[0] = kind.toByte()
                for (i in 0 until 8) payload[1 + i] = (pts ushr (56 - 8 * i)).toByte()
                din.readFully(payload, 9, n)
                when (kind.toChar()) {
                    'I' -> {
                        val f = String(payload, 9, n).trim().split(' ').map { it.toInt() }
                        s.ready.complete(JSONObject().put("w", f[0]).put("h", f[1]).put("sw", f[2]).put("sh", f[3]))
                    }
                    'C' -> if (!s.queue.offer(payload, 1, TimeUnit.SECONDS)) throw IOException("sender stuck")
                    'K' -> {
                        dropping = false
                        if (!s.queue.offer(payload)) {
                            // Behind: the frames waiting are dropped, this key frame starts again from scratch.
                            s.queue.removeIf { it[0] != 'C'.code.toByte() }
                            if (!s.queue.offer(payload, 1, TimeUnit.SECONDS)) throw IOException("sender stuck")
                        }
                    }
                    else -> {
                        // A frame dropped breaks the next ones until a key frame: they all wait for it.
                        if (dropping) continue
                        if (!s.queue.offer(payload)) {
                            dropping = true
                            input(s.ch, "s")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (s.open) Log.i(TAG, "helper ended: ${e.message}")
        }
        s.ready.completeExceptionally(BridgeException(reason(s) ?: app.getString(R.string.screen_failed)))
        end(s, reason(s) ?: app.getString(R.string.screen_failed))
    }

    private fun send(s: Session) {
        try {
            while (s.open) s.ch.sendBinary(s.queue.take())
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            // The phone's link closed: its connection ends the share too.
            end(s, null)
        }
    }

    private fun end(
        s: Session,
        why: String?,
    ) {
        synchronized(s) {
            if (!s.open) return
            s.open = false
        }
        // End of stdin: the helper releases the encoder and the display, then exits (its next packet fails too, its
        // output closed); killed if it does not.
        runCatching { s.stdin.close() }
        runCatching { s.process.inputStream.close() }
        thread("screen-end") {
            if (!s.process.waitFor(STOP_MS, TimeUnit.MILLISECONDS)) s.process.destroy()
        }
        s.sender?.interrupt()
        s.queue.clear()
        synchronized(this) { if (session === s) session = null }
        if (session == null) app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        Log.i(TAG, "screen share with ${s.phone} ended${why?.let { ": $it" } ?: ""}")
        if (why != null) {
            runCatching {
                s.ch.send(
                    buildJsonObject {
                        put("ev", "screen")
                        put(
                            "d",
                            buildJsonObject {
                                put("on", false)
                                put("e", why)
                            },
                        )
                    },
                )
            }
        }
    }

    private fun reason(s: Session): String? =
        synchronized(s.errors) { s.errors.lastOrNull { it.startsWith("error=") }?.removePrefix("error=") ?: s.errors.lastOrNull() }

    private fun notify(phone: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.screen_channel), NotificationManager.IMPORTANCE_LOW))
        val stop =
            PendingIntent.getBroadcast(
                app,
                NOTIFICATION,
                Intent(app, ScreenStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        nm.notify(
            NOTIFICATION,
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_headunit)
                .setContentTitle(app.getString(R.string.screen_shared, phone))
                .setContentText(app.getString(R.string.screen_shared_text))
                .setOngoing(true)
                .addAction(
                    Notification.Action
                        .Builder(
                            android.graphics.drawable.Icon
                                .createWithResource(app, R.drawable.ic_stat_headunit),
                            app.getString(R.string.screen_stop),
                            stop,
                        ).build(),
                ).build(),
        )
    }

    private fun thread(
        name: String,
        block: () -> Unit,
    ): Thread =
        Thread(block, name).apply {
            isDaemon = true
            start()
        }

    companion object {
        private const val TAG = "LibreHU-Screen"
        private const val CHANNEL = "screen_share"
        private const val NOTIFICATION = 0x5C4EE
        private const val START_S = 20L
        private const val STOP_MS = 1_500L

        /** Packets waiting at most (about a quarter of a second at 30 frames a second). */
        private const val QUEUE = 8
        private const val MAX_PACKET = 3 * 1024 * 1024

        @Volatile
        private var instance: ScreenShare? = null

        fun get(context: Context): ScreenShare = instance ?: synchronized(this) { instance ?: ScreenShare(context).also { instance = it } }
    }
}

/** "Stop" of the screen sharing notification. */
class ScreenStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        ScreenShare.get(context).stopAll()
    }
}
