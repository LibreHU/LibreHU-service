package org.librehu.service.net.cable

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import org.librehu.core.relay.Ipv4Framer
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The head unit's VPN while the phone gives its Internet over the cable ([CableInternet]): every IPv4 packet of the apps
 * goes into the adb stream to the phone's relay, the answers come back into the VPN. Same settings as gnirehtet's
 * Android app (GnirehtetService: 10.0.0.2/32, route 0.0.0.0/0, DNS 8.8.8.8, MTU 0x4000); IPv4 only, like the relay.
 * Android Auto and CarPlay apps stay outside (their local Wi-Fi links).
 */
class CableVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val threads = ArrayList<Thread>()

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            close()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) open()
        return START_NOT_STICKY
    }

    private fun open() {
        val stream = CableInternet.tunnel ?: run {
            stopSelf()
            return
        }
        val b =
            Builder()
                .setSession("LibreHU: phone Internet over the cable")
                .addAddress(ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS)
                .setMtu(MTU)
                .setBlocking(true)
        for (p in OUTSIDE) runCatching { b.addDisallowedApplication(p) }
        val fd = b.establish() ?: run {
            CableInternet.vpnEnded("VPN refused by Android")
            stopSelf()
            return
        }
        tun = fd
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val queue = LinkedBlockingQueue<ByteArray>(QUEUE)
        // Packets of the apps: one read per packet; the sender joins those waiting into one adb message.
        threads +=
            thread("cable-vpn-read") {
                val buf = ByteArray(MTU)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    queue.put(buf.copyOf(n))
                }
            }
        threads +=
            thread("cable-vpn-send") {
                val batch = java.io.ByteArrayOutputStream(1 shl 16)
                while (true) {
                    val first = queue.poll(1, TimeUnit.SECONDS) ?: if (stream.closed) break else continue
                    batch.reset()
                    batch.write(first)
                    while (batch.size() < BATCH) batch.write(queue.poll() ?: break)
                    val bytes = batch.toByteArray()
                    stream.write(bytes)
                    CableInternet.count(bytes.size.toLong(), 0)
                }
            }
        // Answers of the phone: a byte stream, cut into IPv4 packets by their total length, one write each.
        threads +=
            thread("cable-vpn-receive") {
                val framer = Ipv4Framer()
                while (true) {
                    val chunk = stream.readChunk() ?: break
                    CableInternet.count(0, chunk.size.toLong())
                    framer.feed(chunk) { p, off, len -> output.write(p, off, len) }
                }
            }
        Log.i(TAG, "VPN up")
    }

    private fun thread(
        name: String,
        body: () -> Unit,
    ) = Thread({
        try {
            body()
        } catch (e: Exception) {
            Log.i(TAG, "$name: $e")
        } finally {
            CableInternet.vpnEnded("$name ended")
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    override fun onRevoke() {
        CableInternet.vpnEnded("VPN revoked (another VPN, or switched off in Android's settings)")
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private fun close() {
        val t = tun ?: return
        tun = null
        runCatching { t.close() }
        for (th in threads) th.interrupt()
        threads.clear()
        Log.i(TAG, "VPN down")
    }

    companion object {
        private const val TAG = "LibreHU-CableVpn"
        private const val ACTION_STOP = "org.librehu.service.CABLE_VPN_STOP"
        const val ADDRESS = "10.0.0.2"
        const val DNS = "8.8.8.8"
        const val MTU = 0x4000
        private const val QUEUE = 512
        private const val BATCH = 128 * 1024
        private val OUTSIDE = listOf("org.librehu.headunit", "com.andrerinas.headunitrevived", "org.librehu.carplay")

        fun start(context: Context) {
            context.startService(Intent(context, CableVpnService::class.java))
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, CableVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
