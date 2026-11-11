package org.librehu.service.net.cable

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.librehu.core.adb.AdbConnection
import org.librehu.core.adb.AdbKey
import org.librehu.core.adb.AdbServices
import org.librehu.core.adb.AdbStream
import org.librehu.core.relay.PhoneRelay
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The phone's Internet over the USB cable while it runs Android Auto (wired): Android Auto takes the phone's USB in
 * accessory mode, where Android allows no USB tethering (one USB function at a time), but a phone with USB debugging on
 * shows a second interface, adb's (0x18d1:0x2d01). Open Headunit uses the first one; this uses adb's:
 *
 * 1. the USB device for LibreHU-service without a dialog (root helper [UsbGrant]), adb's interface claimed;
 * 2. adb connection as a host ([AdbConnection]): the phone asks its user once to allow this head unit ("Always allow");
 * 3. LibreHU-service's own APK copied to the phone (/data/local/tmp, `sync:`) when it changed, and its relay started as
 *    the shell user (`exec:… app_process … PhoneRelay`): gnirehtet's relay, which opens the connections to the Internet;
 * 4. an adb stream to the relay (`tcp:<port>`, the secret first), then [CableVpnService]: a VPN of the head unit whose
 *    IPv4 packets go through that stream.
 *
 * Off by default (KEY_ENABLED). Status in [status], for the network screen.
 */
object CableInternet {
    private const val TAG = "LibreHU-Cable"
    const val KEY_ENABLED = "cable_internet"

    /** On the phone: the APK used as the relay's class path, and its log. */
    const val REMOTE_APK = "/data/local/tmp/librehu-relay.apk"
    private const val REMOTE_LOG = "/data/local/tmp/librehu-relay.log"

    private const val ADB_CLASS = 0xff
    private const val ADB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 1
    private const val AUTHORIZE_MS = 180_000L
    private const val RELAY_MS = 20_000L
    private const val POLL_MS = 3_000L

    enum class Phase { OFF, NO_PHONE, NO_DEBUGGING, PERMISSION, CONNECTING, AUTHORIZE, RELAY, CONNECTED, ERROR }

    data class Status(
        val phase: Phase = Phase.OFF,
        val detail: String = "",
        val device: String = "",
        val sent: Long = 0,
        val received: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** The stream to the relay while connected; [CableVpnService] forwards the VPN's packets through it. */
    @Volatile
    var tunnel: AdbStream? = null
        private set

    @Volatile
    private var session: Session? = null

    @Volatile
    private var started = false
    private lateinit var app: Context

    fun enabled(context: Context) = LibreHuService.prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        LibreHuService.prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) session?.end("switched off")
        update()
    }

    /** Called by LibreHuService: watches the USB devices (plug and unplug, and a poll every [POLL_MS]). */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        app.registerReceiver(receiver, filter)
        Thread({
            while (started) {
                runCatching { update() }
                Thread.sleep(POLL_MS)
            }
        }, "cable-watch").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(receiver) }
        session?.end("service stopped")
    }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    @Suppress("DEPRECATION")
                    val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    session?.let { s -> if (d != null && d.deviceName == s.device.deviceName) s.end("phone unplugged") }
                }
                update()
            }
        }

    /** The USB interface of adb on [device], or null (USB debugging off on the phone, or not a phone). */
    fun adbInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
            it.interfaceClass == ADB_CLASS && it.interfaceSubclass == ADB_SUBCLASS && it.interfaceProtocol == ADB_PROTOCOL
        }

    /** An Android Auto phone (accessory mode, Google's 0x18d1:0x2d0x). */
    private fun isAccessory(d: UsbDevice) = d.vendorId == 0x18d1 && d.productId in 0x2d00..0x2d05

    @Synchronized
    private fun update() {
        if (!started) return
        if (!enabled(app)) {
            if (session == null) set(Status(Phase.OFF))
            return
        }
        if (session != null) return
        val usb = app.getSystemService(UsbManager::class.java) ?: return
        val devices = usb.deviceList.values
        val phone = devices.firstOrNull { adbInterface(it) != null && isAccessory(it) } ?: devices.firstOrNull { adbInterface(it) != null }
        if (phone == null) {
            set(Status(if (devices.any { isAccessory(it) }) Phase.NO_DEBUGGING else Phase.NO_PHONE))
            return
        }
        val s = Session(phone)
        session = s
        Thread({
            try {
                s.run()
            } catch (e: Exception) {
                Log.w(TAG, "session: $e")
                if (!s.ended) set(Status(Phase.ERROR, e.message ?: e.javaClass.simpleName, s.label))
            } finally {
                s.end(null)
                synchronized(this) { if (session === s) session = null }
            }
        }, "cable-session").apply {
            isDaemon = true
            start()
        }
    }

    private fun set(s: Status) {
        if (_status.value != s) _status.value = s
    }

    /** Bytes through the tunnel (CableVpnService). */
    fun count(
        sent: Long,
        received: Long,
    ) {
        val s = _status.value
        if (s.phase == Phase.CONNECTED) _status.value = s.copy(sent = s.sent + sent, received = s.received + received)
    }

    /** The VPN ended on its own (revoked by the user or by another VPN app). */
    fun vpnEnded(why: String) {
        session?.end(why)
    }

    private fun key(context: Context): AdbKey {
        val f = File(context.createDeviceProtectedStorageContext().filesDir, "adbkey")
        runCatching { return AdbKey.fromPkcs8(f.readBytes()) }
        val k = AdbKey.generate()
        f.writeBytes(k.encoded())
        return k
    }

    private fun sha256(f: File): String =
        FileInputStream(f).use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

    /** One phone, from the plug to the unplug (or the switch off). */
    private class Session(
        val device: UsbDevice,
    ) {
        val label = "${device.manufacturerName ?: ""} ${device.productName ?: ""}".trim().ifEmpty { device.deviceName }

        @Volatile
        var ended = false
        private var adb: AdbConnection? = null
        private var relay: AdbStream? = null
        private var connection: android.hardware.usb.UsbDeviceConnection? = null
        private var claimed: UsbInterface? = null
        private val done = Object()

        fun run() {
            val usb = app.getSystemService(UsbManager::class.java)
            val iface = adbInterface(device) ?: return
            set(Status(Phase.PERMISSION, "", label))
            if (!usb.hasPermission(device)) grant(usb)
            if (!usb.hasPermission(device)) throw IOException("USB permission refused")
            val conn = usb.openDevice(device) ?: throw IOException("USB device not opened")
            connection = conn
            if (!conn.claimInterface(iface, true)) throw IOException("adb interface busy")
            claimed = iface
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp == null || outEp == null) throw IOException("adb interface without bulk endpoints")
            val transport = UsbAdbTransport(conn, inEp, outEp) {}
            val name = "librehu@" + (android.os.Build.MODEL ?: "headunit").replace(' ', '-')
            val c = AdbConnection(transport, key(app), name)
            adb = c
            c.onClosed = { end("phone link lost") }
            set(Status(Phase.CONNECTING, "", label))
            watchdog(AUTHORIZE_MS) { if (c.banner.isEmpty()) end("not allowed on the phone in time") }
            c.connect { set(Status(Phase.AUTHORIZE, "", label)) }
            if (ended) return
            set(Status(Phase.RELAY, "", label))
            startRelay(c)
            if (ended) return
            ensureVpnConsent()
            set(Status(Phase.CONNECTED, c.banner.substringAfter("ro.product.model=", "").substringBefore(';'), label))
            CableVpnService.start(app)
            synchronized(done) { while (!ended) done.wait() }
        }

        private fun startRelay(c: AdbConnection) {
            val apk = File(app.applicationInfo.sourceDir)
            val local = sha256(apk)
            val remote = AdbServices.run(c, "sha256sum $REMOTE_APK 2>/dev/null")?.substringBefore(' ')?.trim()
            if (remote != local) {
                Log.i(TAG, "relay: copying ${apk.length()} bytes to the phone")
                FileInputStream(apk).use { AdbServices.push(c, it, REMOTE_APK) }
            }
            val r =
                c.open("exec:CLASSPATH=$REMOTE_APK exec app_process / ${PhoneRelay::class.java.name} 2>$REMOTE_LOG")
                    ?: throw IOException("relay not started on the phone")
            relay = r
            // The relay's first line: LIBREHU-RELAY <port> <secret>.
            watchdog(RELAY_MS) { if (tunnel == null) end("relay did not answer") }
            val line = StringBuilder()
            val input = r.inputStream
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("relay ended: " + (AdbServices.run(c, "tail -n 5 $REMOTE_LOG") ?: "").trim())
                if (b == '\n'.code) break
                line.append(b.toChar())
            }
            val parts = line.toString().trim().split(' ')
            if (parts.size != 3 || parts[0] != PhoneRelay.BANNER) throw IOException("relay: unexpected answer $line")
            val secret = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val t = c.open("tcp:${parts[1]}") ?: throw IOException("relay port refused")
            t.write(secret)
            DataInputStream(t.inputStream).readInt() // the client id of gnirehtet's protocol
            tunnel = t
            // Keep reading the exec stream (the relay's standard output) so it never blocks.
            Thread({
                while (r.readChunk() != null) {
                }
                end("relay ended")
            }, "cable-relay-out").apply {
                isDaemon = true
                start()
            }
        }

        private fun grant(usb: UsbManager) {
            val apk = app.applicationInfo.sourceDir
            val out =
                RootShell.run(
                    "CLASSPATH='$apk' app_process / ${UsbGrant::class.java.name} '${device.deviceName}' ${app.applicationInfo.uid}",
                    timeoutS = 15,
                )
            if (out?.contains("granted") == true) return
            // Without root: Android's dialog (on the head unit's screen).
            val intent = PendingIntent.getBroadcast(app, 0, Intent("org.librehu.service.USB_PERMISSION").setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
            usb.requestPermission(device, intent)
            val end = System.currentTimeMillis() + 60_000
            while (!usb.hasPermission(device) && System.currentTimeMillis() < end && !ended) Thread.sleep(500)
        }

        /** VPN allowed for LibreHU-service: through root (app op ACTIVATE_VPN), else the user agreed from the screen. */
        private fun ensureVpnConsent() {
            if (VpnService.prepare(app) == null) return
            RootShell.run("appops set ${app.packageName} ACTIVATE_VPN allow")
            if (VpnService.prepare(app) != null) throw IOException("VPN not allowed: switch the option off and on again on the head unit's screen")
        }

        private fun watchdog(
            ms: Long,
            check: () -> Unit,
        ) = Thread({
            Thread.sleep(ms)
            if (!ended) check()
        }, "cable-watchdog").apply {
            isDaemon = true
            start()
        }

        fun end(why: String?) {
            synchronized(this) {
                if (ended) return
                ended = true
            }
            if (why != null) Log.i(TAG, "end: $why")
            CableVpnService.stop(app)
            tunnel = null
            runCatching { relay?.close() }
            runCatching { adb?.close() }
            runCatching { claimed?.let { connection?.releaseInterface(it) } }
            runCatching { connection?.close() }
            synchronized(done) { done.notifyAll() }
            val enabled = enabled(app)
            set(Status(if (!enabled) Phase.OFF else if (why == null || why == "phone unplugged") Phase.NO_PHONE else Phase.ERROR, why ?: "", label))
        }
    }
}
