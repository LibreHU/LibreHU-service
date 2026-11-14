package org.librehu.service.net

import android.os.IBinder
import org.librehu.core.net.IpSettings
import java.net.InetAddress

/**
 * Runs as root in its own process (`CLASSPATH=<LibreHU apk> app_process / org.librehu.service.net.EthernetHelper …`,
 * see [EthernetIp]): calls Android's Ethernet service (`IEthernetManager`, hidden API) as uid 0, which passes its
 * CONNECTIVITY_INTERNAL check, with no hidden API restriction (not an app process). Android keeps what is set
 * (`/data/misc/ethernet/ipconfig.txt`) and restarts the interface with it. One line per result on stdout.
 *
 * - `list` → `<interface>=<settings>` for each interface the service manages ([IpSettings.encode]), then `ok=1`
 * - `set <interface> <settings>` → `ok=1`
 * - errors: `error=<message>`
 */
object EthernetHelper {
    private const val IP_CONFIG = "android.net.IpConfiguration"
    private const val STATIC_CONFIG = "android.net.StaticIpConfiguration"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val eth = service()
            when (args.firstOrNull()) {
                "list" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = eth.javaClass.getMethod("getAvailableInterfaces").invoke(eth) as Array<String>
                    for (name in names) {
                        val config = eth.javaClass.getMethod("getConfiguration", String::class.java).invoke(eth, name)
                        println("$name=${encode(config)}")
                    }
                    println("ok=1")
                }

                "set" -> {
                    val name = args.getOrNull(1) ?: error("no interface")
                    val settings = IpSettings.decode(args.getOrNull(2)) ?: error("bad settings")
                    check(settings.problems().isEmpty()) { "invalid settings: ${settings.problems()}" }
                    eth.javaClass
                        .getMethod("setConfiguration", String::class.java, Class.forName(IP_CONFIG))
                        .invoke(eth, name, build(settings))
                    println("ok=1")
                }

                else -> println("error=usage: list | set <interface> <settings>")
            }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            println("error=${cause.toString().replace('\n', ' ')}")
        }
    }

    /** IEthernetManager proxy of the "ethernet" system service. */
    private fun service(): Any {
        val binder =
            Class
                .forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "ethernet") as IBinder? ?: error("no Ethernet service (android.hardware.ethernet not declared?)")
        return Class
            .forName("android.net.IEthernetManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: error("no IEthernetManager")
    }

    private fun enumValue(
        cls: Class<*>,
        name: String,
    ): Any = cls.getMethod("valueOf", String::class.java).invoke(null, name)!!

    /** IpConfiguration(STATIC or DHCP, no proxy) of [s]. */
    private fun build(s: IpSettings): Any {
        val config = Class.forName(IP_CONFIG)
        val assignment = Class.forName("$IP_CONFIG\$IpAssignment")
        val proxy = Class.forName("$IP_CONFIG\$ProxySettings")
        val staticCls = Class.forName(STATIC_CONFIG)
        val static =
            if (!s.static) {
                null
            } else {
                staticCls.getConstructor().newInstance().also { c ->
                    val link = Class.forName("android.net.LinkAddress").getConstructor(String::class.java).newInstance("${s.address}/${s.prefix}")
                    staticCls.getField("ipAddress").set(c, link)
                    if (s.gateway.isNotEmpty()) staticCls.getField("gateway").set(c, InetAddress.getByName(s.gateway))
                    @Suppress("UNCHECKED_CAST")
                    val dns = staticCls.getField("dnsServers").get(c) as MutableList<InetAddress>
                    s.dns.forEach { dns += InetAddress.getByName(it) }
                }
            }
        return config
            .getConstructor(assignment, proxy, staticCls, Class.forName("android.net.ProxyInfo"))
            .newInstance(enumValue(assignment, if (s.static) "STATIC" else "DHCP"), enumValue(proxy, "NONE"), static, null)
    }

    /** [IpSettings.encode] of an IpConfiguration (Android 9: StaticIpConfiguration's public fields). */
    private fun encode(config: Any?): String {
        if (config == null) return IpSettings.DHCP
        val cls = Class.forName(IP_CONFIG)
        if (cls.getMethod("getIpAssignment").invoke(config).toString() != "STATIC") return IpSettings.DHCP
        val static = cls.getMethod("getStaticIpConfiguration").invoke(config) ?: return IpSettings.DHCP
        val staticCls = Class.forName(STATIC_CONFIG)
        val link = staticCls.getField("ipAddress").get(static) ?: return IpSettings.DHCP
        val address = link.javaClass.getMethod("getAddress").invoke(link) as InetAddress
        val prefix = link.javaClass.getMethod("getPrefixLength").invoke(link) as Int
        val gateway = staticCls.getField("gateway").get(static) as InetAddress?

        @Suppress("UNCHECKED_CAST")
        val dns = staticCls.getField("dnsServers").get(static) as List<InetAddress>
        return IpSettings(true, address.hostAddress.orEmpty(), prefix, gateway?.hostAddress.orEmpty(), dns.mapNotNull { it.hostAddress }).encode()
    }
}
