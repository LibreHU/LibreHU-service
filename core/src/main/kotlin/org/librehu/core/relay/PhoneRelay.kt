package org.librehu.core.relay

import com.genymobile.gnirehtet.relay.Client
import com.genymobile.gnirehtet.relay.SelectionHandler
import com.genymobile.gnirehtet.relay.TunnelServer
import com.genymobile.gnirehtet.relay.UDPConnection
import java.nio.channels.Selector
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * The relay run on the phone for the head unit's Internet over the USB cable, as the shell user, started through adb
 * (`CLASSPATH=<LibreHU-service apk> app_process / org.librehu.core.relay.PhoneRelay`): the relay of gnirehtet
 * (Genymobile, Apache-2.0, com.genymobile.gnirehtet.relay), roles reversed: the head unit sends the IPv4 packets of its
 * VPN through an adb stream, the phone opens the TCP and UDP connections to the Internet and sends the answers back.
 *
 * Listens on 127.0.0.1, free port; prints `LIBREHU-RELAY <port> <secret>` on its standard output, which only adb's exec
 * stream (the head unit) sees; the client sends the 16-byte secret first (other apps of the phone can reach 127.0.0.1).
 * Ends when its standard input ends (the head unit closed the stream, cable unplugged), when its client leaves, or when
 * none came within [FIRST_CLIENT_MS]. Logs on the standard error.
 */
object PhoneRelay {
    const val BANNER = "LIBREHU-RELAY"
    const val SECRET_SIZE = 16
    private const val FIRST_CLIENT_MS = 60_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val secret = ByteArray(SECRET_SIZE).also { SecureRandom().nextBytes(it) }
        val loop = RelayLoop(secret) { exitProcess(0) }
        println("$BANNER ${loop.port} ${secret.joinToString("") { "%02x".format(it) }}")
        System.out.flush()
        daemon("relay-stdin") {
            // The head unit's end of the exec stream: when it closes, the relay is no longer wanted.
            try {
                while (System.`in`.read() >= 0) {
                }
            } catch (_: Exception) {
            }
            exitProcess(0)
        }
        daemon("relay-first-client") {
            Thread.sleep(FIRST_CLIENT_MS)
            if (!loop.served) exitProcess(2)
        }
        loop.run()
    }

    private fun daemon(
        name: String,
        body: () -> Unit,
    ) = Thread(body, name).apply {
        isDaemon = true
        start()
    }
}

/** gnirehtet's relay loop (Relay.run) on a [TunnelServer] of a free port with [secret]; [onClientGone] when a client left. */
class RelayLoop(
    secret: ByteArray,
    onClientGone: (Client) -> Unit,
) {
    private val selector = Selector.open()
    private val server = TunnelServer(0, selector, secret) { onClientGone(it) }
    val port: Int get() = server.port

    /** A client got through (secret checked). */
    @Volatile
    var served = false
        private set

    @Volatile
    private var stopped = false

    fun run() {
        var nextCleaning = System.currentTimeMillis() + UDPConnection.IDLE_TIMEOUT
        while (!stopped) {
            selector.select(maxOf(1L, nextCleaning - System.currentTimeMillis()))
            val keys = selector.selectedKeys()
            val now = System.currentTimeMillis()
            if (now >= nextCleaning || keys.isEmpty()) {
                server.cleanUp()
                nextCleaning = now + CLEANING_INTERVAL_MS
            }
            for (key in keys) (key.attachment() as SelectionHandler).onReady(key)
            if (server.clientCount() > 0) served = true
            keys.clear()
        }
        selector.close()
    }

    fun stop() {
        stopped = true
        selector.wakeup()
    }

    private companion object {
        const val CLEANING_INTERVAL_MS = 60_000L
    }
}
