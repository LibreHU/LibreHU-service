package org.librehu.service.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import org.librehu.core.net.IpSettings
import org.librehu.service.root.RootShell
import java.net.Inet4Address

/**
 * IP settings of the wired interfaces that Android's Ethernet service manages (`(eth|usb)\d+` with LibreHU): USB
 * Ethernet adapters, Android phones' RNDIS tethering, iPhone tethering (ipheth). Set as root through [EthernetHelper];
 * Android keeps them for the interface's name and restarts it with them.
 *
 * Interface names are not stable (an iPhone and an adapter share eth0 / eth1 in plug order; an Android phone's RNDIS
 * gets a new MAC address every time), so LibreHU keeps one profile per kind of device ([key]) and applies it to the
 * matching interface when it appears ([applyProfiles]: after a USB plug-in, at start).
 */
object EthernetIp {
    private const val TAG = "LibreHU-EthIp"
    private const val PREFS = "ethernet_ip"

    /** Profile of an interface: one for Android phones (RNDIS), one for iPhones, one per adapter (its MAC address). */
    fun key(iface: UsbNet.NetInterface): String =
        when (iface.driver) {
            "rndis_host" -> "rndis"
            "ipheth" -> "iphone"
            else -> "mac:" + iface.mac.lowercase()
        }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved profile of [key], or null (Android's default: DHCP). */
    fun profile(
        context: Context,
        key: String,
    ): IpSettings? = IpSettings.decode(prefs(context).getString(key, null))

    private fun hasProfiles(context: Context): Boolean = prefs(context).all.isNotEmpty()

    /** Android's settings of each interface its Ethernet service manages; null without root or on an error. */
    fun current(context: Context): Map<String, IpSettings>? {
        val out = helper(context, "list") ?: return null
        if (out["ok"] != "1") return null
        return out.filterKeys { it != "ok" }.mapNotNull { (k, v) -> IpSettings.decode(v)?.let { k to it } }.toMap()
    }

    /** Saves [settings] as [key]'s profile and applies them to [iface] now; the error text, or null when done. */
    fun save(
        context: Context,
        key: String,
        iface: String,
        settings: IpSettings,
    ): String? {
        if (settings.problems().isNotEmpty()) return settings.problems().joinToString()
        val r = helper(context, "set ${RootShell.quote(iface)} ${RootShell.quote(settings.encode())}") ?: return "root"
        if (r["ok"] != "1") return r["error"] ?: "?"
        prefs(context).edit().putString(key, settings.encode()).apply()
        Log.i(TAG, "$iface ($key): ${settings.encode()}")
        return null
    }

    /**
     * Applies the saved profiles to the interfaces present whose Android settings differ (after a USB plug-in, at
     * start). Returns the interfaces changed. Blocking (root, sysfs): not on the main thread.
     */
    fun applyProfiles(context: Context): List<String> {
        if (!hasProfiles(context) || !RootShell.isAvailable()) return emptyList()
        val current = current(context) ?: return emptyList()
        val changed = ArrayList<String>()
        for (iface in UsbNet.scan(context).interfaces) {
            val wanted = profile(context, key(iface)) ?: continue
            if (iface.name !in current || current[iface.name] == wanted) continue
            val r = helper(context, "set ${RootShell.quote(iface.name)} ${RootShell.quote(wanted.encode())}")
            if (r?.get("ok") == "1") {
                changed += iface.name
                Log.i(TAG, "${iface.name} (${key(iface)}): profile applied, ${wanted.encode()}")
            } else {
                Log.w(TAG, "${iface.name}: profile not applied: ${r?.get("error") ?: "no root"}")
            }
        }
        return changed
    }

    /** Address, gateway and DNS that Android uses on [iface] now (its link properties), or null when it has no network there. */
    fun live(
        context: Context,
        iface: String,
    ): LinkProperties? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks
            .asSequence()
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
            .mapNotNull { cm.getLinkProperties(it) }
            .firstOrNull { it.interfaceName == iface }
    }

    /** One line for people: `192.168.1.50/24 · gw 192.168.1.1 · DNS 1.1.1.1` of [lp] (IPv4 only). */
    fun describe(lp: LinkProperties): String =
        listOfNotNull(
            lp.linkAddresses.filter { it.address is Inet4Address }.joinToString(", ") { "${it.address.hostAddress}/${it.prefixLength}" }.ifEmpty { null },
            lp.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
                ?.hostAddress
                ?.let { "gw $it" },
            lp.dnsServers.filterIsInstance<Inet4Address>().joinToString(", ") { it.hostAddress.orEmpty() }.ifEmpty { null }?.let { "DNS $it" },
        ).joinToString(" · ")

    private fun helper(
        context: Context,
        args: String,
    ): Map<String, String>? {
        if (!RootShell.isAvailable()) return null
        val apk = context.applicationInfo.sourceDir
        val out = RootShell.run("CLASSPATH='$apk' app_process /system/bin ${EthernetHelper::class.java.name} $args", timeoutS = 20) ?: return null
        return out
            .lineSequence()
            .mapNotNull { line ->
                line
                    .trim()
                    .split('=', limit = 2)
                    .takeIf { it.size == 2 }
                    ?.let { it[0] to it[1] }
            }.toMap()
    }
}
