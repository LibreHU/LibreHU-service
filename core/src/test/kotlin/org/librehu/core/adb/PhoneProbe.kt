package org.librehu.core.adb

import org.librehu.core.relay.Packets
import org.librehu.core.relay.PhoneRelay
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * The Internet over the cable end to end against a real adbd (CI: .github/workflows/phone-internet-test.yml, on an
 * emulator standing for the phone): adb connection with the key, LibreHU-service's APK pushed, the relay started with
 * app_process as the shell user, the tunnel opened with the secret, then a DNS query (UDP) and an HTTP request (TCP)
 * through the relay, built packet by packet as the head unit's VPN would send them. Exit 1 on a failure.
 *
 *     ./gradlew -PcoreOnly :core:phoneProbe -PprobeArgs="127.0.0.1 5555 adbkey LibreHU-service.apk"
 */
fun main(args: Array<String>) {
    val (host, port, keyFile, apkPath) = args
    val results = ArrayList<String>()
    var failed = false

    fun check(
        what: String,
        ok: Boolean,
        detail: String = "",
    ) {
        results += "$what: ${if (ok) "ok" else "FAILED"}${if (detail.isNotEmpty()) " ($detail)" else ""}"
        if (!ok) failed = true
        println(results.last())
    }

    try {
        val socket = Socket(host, port.toInt())
        socket.soTimeout = 0
        val key = AdbKey.fromPem(File(keyFile).readText())
        val c = AdbConnection(StreamTransport(socket.getInputStream(), socket.getOutputStream()) { socket.close() }, key, "probe@librehu")
        var asked = false
        c.connect { asked = true }
        check("adb connection (key already allowed)", !asked, c.banner.take(120))
        val sdk = AdbServices.run(c, "getprop ro.build.version.sdk")?.trim()
        check("exec", sdk?.toIntOrNull() != null, "SDK $sdk")

        val apk = File(apkPath)
        val local = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        val t0 = System.nanoTime()
        FileInputStream(apk).use { AdbServices.push(c, it, "/data/local/tmp/librehu-relay.apk") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val remote = AdbServices.run(c, "sha256sum /data/local/tmp/librehu-relay.apk")?.substringBefore(' ')?.trim()
        check("push ${apk.length()} bytes", remote == local, "$ms ms")

        val relay =
            c.open("exec:CLASSPATH=/data/local/tmp/librehu-relay.apk exec app_process / ${PhoneRelay::class.java.name} 2>/data/local/tmp/librehu-relay.log")
                ?: error("exec refused")
        val line = StringBuilder()
        val rin = relay.inputStream
        while (true) {
            val b = rin.read()
            if (b < 0 || b == '\n'.code) break
            line.append(b.toChar())
        }
        val parts = line.toString().trim().split(' ')
        val started = parts.size == 3 && parts[0] == PhoneRelay.BANNER
        check("relay started by app_process", started, line.toString().ifEmpty { AdbServices.run(c, "cat /data/local/tmp/librehu-relay.log") ?: "" }.take(400))
        if (!started) throw IllegalStateException("no relay")
        val tunnel = c.open("tcp:${parts[1]}") ?: error("tcp refused")
        tunnel.write(parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val tin = DataInputStream(tunnel.inputStream)
        val id = tin.readInt()
        check("tunnel (secret accepted)", true, "client $id")
        val vpn = (10 shl 24) or 2
        fun send(p: ByteArray) = tunnel.write(p)

        // DNS: example.com, type A, to 8.8.8.8 (what the VPN's DNS server is).
        val dns = (10 shl 24) or 2
        val google = (8 shl 24) or (8 shl 16) or (8 shl 8) or 8
        val query =
            ByteBuffer.allocate(512).apply {
                putShort(0x1234).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0)
                for (label in listOf("example", "com")) {
                    put(label.length.toByte())
                    put(label.toByteArray())
                }
                put(0).putShort(1).putShort(1)
            }.let { it.array().copyOf(it.position()) }
        send(Packets.udp(dns, 53000, google, 53, query))
        var answer: Packets.Parsed? = null
        while (answer == null) {
            val p = Packets.read(tin)
            if (p.protocol == 17 && p.srcPort == 53) answer = p
        }
        val a = answer.payload
        val count = ((a[6].toInt() and 0xff) shl 8) or (a[7].toInt() and 0xff)
        val ip = firstA(a)
        check("DNS over UDP through the relay", count > 0 && ip != 0, "$count answers, ${(ip ushr 24) and 255}.${(ip ushr 16) and 255}.${(ip ushr 8) and 255}.${ip and 255}")

        // HTTP over TCP to that address.
        send(Packets.tcp(vpn, 41000, ip, 80, seq = 5000, ack = 0, flags = Packets.SYN))
        var synAck: Packets.Parsed? = null
        while (synAck == null) {
            val p = Packets.read(tin)
            if (p.protocol == 6 && p.flags and Packets.SYN != 0) synAck = p
        }
        check("TCP connect through the relay", synAck.ack == 5001L)
        var theirs = synAck.seq + 1
        val request = "HEAD / HTTP/1.0\r\nHost: example.com\r\n\r\n".toByteArray()
        send(Packets.tcp(vpn, 41000, ip, 80, seq = 5001, ack = theirs, flags = Packets.ACK))
        send(Packets.tcp(vpn, 41000, ip, 80, seq = 5001, ack = theirs, flags = Packets.ACK or Packets.PSH, data = request))
        val reply = StringBuilder()
        val deadline = System.currentTimeMillis() + 20_000
        while (!reply.contains("\r\n\r\n") && System.currentTimeMillis() < deadline) {
            val p = Packets.read(tin)
            if (p.protocol != 6 || p.payload.isEmpty()) continue
            reply.append(String(p.payload))
            theirs = p.seq + p.payload.size
            send(Packets.tcp(vpn, 41000, ip, 80, seq = 5001L + request.size, ack = theirs, flags = Packets.ACK))
        }
        check("HTTP over TCP through the relay", reply.startsWith("HTTP/1."), reply.lineSequence().firstOrNull() ?: "")
        relay.close()
        c.close()
    } catch (e: Exception) {
        check("probe", false, e.toString())
    }
    println(results.joinToString("\n", "\n== summary\n"))
    System.getenv("GITHUB_STEP_SUMMARY")?.let { File(it).appendText("## Internet over the cable (adb + relay)\n\n```\n${results.joinToString("\n")}\n```\n") }
    exitProcess(if (failed) 1 else 0)
}

/** The first IPv4 address (type A) of a DNS answer (RFC 1035, 4.1), 0 if none. */
private fun firstA(a: ByteArray): Int {
    fun u16(i: Int) = ((a[i].toInt() and 0xff) shl 8) or (a[i + 1].toInt() and 0xff)

    fun skipName(start: Int): Int {
        var i = start
        while (true) {
            val len = a[i].toInt() and 0xff
            if (len == 0) return i + 1
            if (len and 0xc0 == 0xc0) return i + 2
            i += len + 1
        }
    }
    var i = 12
    repeat(u16(4)) { i = skipName(i) + 4 }
    repeat(u16(6)) {
        i = skipName(i)
        val type = u16(i)
        val length = u16(i + 8)
        if (type == 1 && length == 4) return ByteBuffer.wrap(a, i + 10, 4).int
        i += 10 + length
    }
    return 0
}
