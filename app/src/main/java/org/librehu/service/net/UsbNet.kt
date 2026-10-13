package org.librehu.service.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.librehu.service.root.RootShell

/**
 * Wired networks over USB: Ethernet adapters and USB tethering of a phone.
 *
 * The stock kernel (4.9.117+) has the ASIX (AX88179, AX88772) and generic CDC ECM / NCM drivers built in; the others
 * (Realtek RTL8152 / RTL8153, Android RNDIS tethering, iPhone, LAN78xx, SMSC, Davicom, CoreChips…) come as modules
 * from the flashable zip `usb-net` (LibreHU-flashablesZips, kernel category): `/vendor/lib/modules/librehu-usbnet`,
 * loaded by `/vendor/etc/init/init.librehu_usbnet.rc` once Android has started. That script keeps a crash guard:
 * `persist.librehu.usbnet.off` = 1 after a restart during the loading (or when switched off here), and
 * `sys.librehu.usbnet.arm` = 1 loads them now. Android's EthernetService (started because of the USB host feature)
 * takes the `eth*` interfaces with DHCP.
 */
object UsbNet {
    private const val TAG = "LibreHU-UsbNet"
    const val MODULE_DIR = "/vendor/lib/modules/librehu-usbnet"
    const val APPLE_VID = 0x05ac

    enum class Kind { ETHERNET, ANDROID_PHONE, IPHONE, OTHER }

    /** Known USB network chip: name, Linux driver, and whether the stock kernel has it built in. */
    data class Chip(
        val name: String,
        val driver: String,
        val builtIn: Boolean,
    )

    data class UsbInterface(
        val name: String,
        val cls: Int,
        val subClass: Int,
        val protocol: Int,
        val driver: String,
    )

    data class UsbDevice(
        /** sysfs name, e.g. 2-1. */
        val path: String,
        val vid: Int,
        val pid: Int,
        val manufacturer: String,
        val product: String,
        val config: Int,
        val configs: Int,
        val interfaces: List<UsbInterface>,
    ) {
        val id: String get() = "%04x:%04x".format(vid, pid)
        val chip: Chip? get() = CHIPS[id]
        val drivers: List<String> get() = interfaces.map { it.driver }.filter { it.isNotEmpty() && it != "usb" }.distinct()

        val kind: Kind
            get() =
                when {
                    vid == APPLE_VID -> Kind.IPHONE
                    chip != null -> Kind.ETHERNET
                    interfaces.any { it.isRndis() } -> Kind.ANDROID_PHONE
                    interfaces.any { it.cls == 2 && (it.subClass == 6 || it.subClass == 0x0d) } -> Kind.ETHERNET
                    else -> Kind.OTHER
                }

        /** Network driver in use, or null. */
        val netDriver: String? get() = drivers.firstOrNull { it in NET_DRIVERS }
    }

    data class NetInterface(
        val name: String,
        val mac: String,
        val state: String,
        val carrier: Boolean,
        /** Mb/s, -1 when unknown. */
        val speed: Int,
        val driver: String,
        val ipv4: List<String>,
    )

    data class State(
        val root: Boolean = false,
        val devices: List<UsbDevice> = emptyList(),
        val interfaces: List<NetInterface> = emptyList(),
        /** Modules of the zip in /vendor. */
        val installed: List<String> = emptyList(),
        /** Modules of the zip loaded now. */
        val loaded: List<String> = emptyList(),
        /** persist.librehu.usbnet.off = 1: not loaded at start (switched off, or a restart while loading). */
        val off: Boolean = false,
        /** Android has an Ethernet network, and it reaches the Internet. */
        val ethernetUp: Boolean = false,
        val ethernetValidated: Boolean = false,
    ) {
        /** Devices of a network kind (or with a network driver). */
        val netDevices: List<UsbDevice> get() = devices.filter { it.kind != Kind.OTHER || it.netDriver != null }
    }

    private fun UsbInterface.isRndis() = (cls == 0xe0 && subClass == 1 && protocol == 3) || (cls == 2 && subClass == 2 && protocol == 0xff)

    /** Drivers of USB network interfaces (built in or from the zip). */
    private val NET_DRIVERS =
        setOf(
            "r8152", "ax88179_178a", "asix", "cdc_ether", "cdc_ncm", "cdc_subset", "rndis_host", "ipheth", "lan78xx",
            "smsc95xx", "smsc75xx", "dm9601", "sr9700", "sr9800", "mcs7830", "ch9200", "rtl8150", "pegasus", "cdc_eem",
            "zaurus", "net1080",
        )

    private val CHIPS: Map<String, Chip> =
        buildMap {
            fun add(
                name: String,
                driver: String,
                builtIn: Boolean,
                vararg ids: String,
            ) = ids.forEach { put(it, Chip(name, driver, builtIn)) }
            add("Realtek RTL8152 (USB 2, 100 Mb/s)", "r8152", false, "0bda:8152")
            add("Realtek RTL8153 (USB 3, gigabit)", "r8152", false, "0bda:8153", "04e8:a101", "17ef:7205", "17ef:304f", "13b1:0041", "0955:09ff")
            add("ASIX AX88179 / AX88178A (USB 3, gigabit)", "ax88179_178a", true, "0b95:1790", "0b95:178a", "04b4:3610", "2001:4a00", "0df6:0072", "04e8:a100", "17ef:304b")
            add("ASIX AX88772 (USB 2, 100 Mb/s)", "asix", true, "0b95:7720", "0b95:772a", "0b95:772b", "0b95:7e2b", "0b95:1780", "2001:3c05", "2001:1a02")
            add("Microchip LAN7800 / LAN7850", "lan78xx", false, "0424:7800", "0424:7850")
            add("SMSC LAN95xx", "smsc95xx", false, "0424:ec00", "0424:9500", "0424:9505", "0424:9e00", "0424:9e01")
            add("SMSC LAN7500", "smsc75xx", false, "0424:7500", "0424:7505")
            add("Davicom DM9601", "dm9601", false, "0a46:9601", "0a46:9000", "0a46:6688", "0fe6:8101")
            add("CoreChips SR9700", "sr9700", false, "0fe6:9700")
            add("CoreChips SR9800", "sr9800", false, "0fe6:9800")
            add("MosChip MCS7830", "mcs7830", false, "9710:7830", "9710:7832", "9710:7730")
            add("QinHeng CH9200", "ch9200", false, "1a86:e092")
            add("Realtek RTL8150", "rtl8150", false, "0bda:8150")
        }

    /** Reads everything (as root when possible: sysfs and the interfaces are not all readable by apps). */
    fun scan(context: Context): State {
        val root = RootShell.isAvailable()
        val out = if (root) RootShell.run(SCAN, timeoutS = 8).orEmpty() else ""
        val devices = LinkedHashMap<String, UsbDevice>()
        val ifaces = ArrayList<NetInterface>()
        val addrs = HashMap<String, MutableList<String>>()
        val installed = ArrayList<String>()
        val modules = HashSet<String>()
        var off = false
        for (line in out.lineSequence()) {
            val f = line.split('|')
            when (f.firstOrNull()) {
                "D" ->
                    if (f.size >= 9) {
                        devices[f[1]] =
                            UsbDevice(f[1], hex(f[2]), hex(f[3]), f[4], f[5], f[6].toIntOrNull() ?: 0, f[7].toIntOrNull() ?: 0, emptyList())
                    }

                "I" ->
                    if (f.size >= 7) {
                        devices[f[1]]?.let { d ->
                            devices[f[1]] = d.copy(interfaces = d.interfaces + UsbInterface(f[2], hex(f[3]), hex(f[4]), hex(f[5]), f[6]))
                        }
                    }

                "N" ->
                    if (f.size >= 7) {
                        ifaces += NetInterface(f[1], f[2], f[3], f[4] == "1", f[5].toIntOrNull() ?: -1, f[6], emptyList())
                    }

                "A" -> if (f.size >= 3) addrs.getOrPut(f[1]) { ArrayList() } += f[2]
                "M" -> if (f.size >= 2) modules += f[1]
                "F" -> if (f.size >= 2 && f[1].endsWith(".ko")) installed += f[1].removeSuffix(".ko")
                "P" -> if (f.size >= 3 && f[1] == "off") off = f[2].trim() == "1"
            }
        }
        val net =
            ifaces
                .filter { it.driver in NET_DRIVERS || it.name.startsWith("eth") || it.name.startsWith("usb") }
                .map { it.copy(ipv4 = addrs[it.name].orEmpty()) }
        val (up, validated) = ethernet(context)
        return State(
            root = root,
            devices = devices.values.toList(),
            interfaces = net,
            installed = installed.sorted(),
            loaded = installed.filter { it in modules }.sorted(),
            off = off,
            ethernetUp = up,
            ethernetValidated = validated,
        )
    }

    /** Android's own view: an Ethernet network, validated (it reaches the Internet). */
    private fun ethernet(context: Context): Pair<Boolean, Boolean> =
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
            caps.isNotEmpty() to caps.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        }.getOrDefault(false to false)

    /** Loads the zip's modules now, through its init script (same crash guard), and at every start again. */
    fun load(): Boolean = RootShell.run("setprop persist.librehu.usbnet.off 0 && setprop sys.librehu.usbnet.arm 1") != null

    /** Not loaded at the next starts (the ones loaded stay until the restart). */
    fun disableAtStart(): Boolean = RootShell.run("setprop persist.librehu.usbnet.off 1") != null

    /**
     * iPhone: Linux picks its first configuration (picture transfer), without the tethering interface (ipheth:
     * class ff, subclass fd, protocol 1). On a computer usbmuxd switches it; here the configuration that has it is
     * chosen from the raw descriptors. iOS may also want the unit trusted ("Trust this computer"): experimental.
     */
    fun iphoneTethering(device: UsbDevice): Result<Int> =
        runCatching {
            val hex =
                RootShell.run("od -An -tx1 -v /sys/bus/usb/devices/${device.path}/descriptors | tr -d ' \\n'")
                    ?: error("root")
            val bytes = hex.trim().chunked(2).mapNotNull { it.toIntOrNull(16) }
            val config = tetheringConfig(bytes) ?: error("no tethering configuration")
            if (device.config != config) {
                RootShell.run("echo $config > /sys/bus/usb/devices/${device.path}/bConfigurationValue")
                    ?: error("bConfigurationValue")
            }
            Log.i(TAG, "iPhone ${device.path}: configuration $config")
            config
        }

    /** Configuration value whose interfaces include Apple's USB Ethernet (ff/fd/01), from raw sysfs descriptors. */
    internal fun tetheringConfig(raw: List<Int>): Int? {
        var i = raw.getOrNull(0) ?: return null // device descriptor
        var config = 0
        while (i + 1 < raw.size) {
            val len = raw[i]
            if (len < 2) return null
            when (raw[i + 1]) {
                2 -> config = raw.getOrNull(i + 5) ?: 0
                4 ->
                    if (i + 7 < raw.size && raw[i + 5] == 0xff && raw[i + 6] == 0xfd && raw[i + 7] == 1 && config != 0) {
                        return config
                    }
            }
            i += len
        }
        return null
    }

    /**
     * iPhones whose tethering configuration is not selected yet (ipheth loaded), and that no app holds: wired
     * CarPlay (CPLink, CarPlay LibreHU) opens the iPhone through usbfs in another configuration, which a switch
     * would cut.
     */
    fun iphonesToSwitch(state: State): List<UsbDevice> =
        if ("ipheth" !in state.loaded) {
            emptyList()
        } else {
            state.devices.filter { d -> d.kind == Kind.IPHONE && d.netDriver == null && d.interfaces.none { it.driver == "usbfs" } }
        }

    private const val PREF_IPHONE_AUTO = "usbnet_iphone_auto"

    /** Switch an iPhone to its tethering configuration as soon as it is plugged in (off by default: wired CarPlay). */
    fun iphoneAuto(context: Context): Boolean = org.librehu.service.LibreHuService.prefs(context).getBoolean(PREF_IPHONE_AUTO, false)

    fun setIphoneAuto(
        context: Context,
        on: Boolean,
    ) = org.librehu.service.LibreHuService
        .prefs(context)
        .edit()
        .putBoolean(PREF_IPHONE_AUTO, on)
        .apply()

    private fun hex(s: String) = s.trim().toIntOrNull(16) ?: 0

    /** One line per item: D device, I interface, N network interface, A IPv4 address, M module, F zip module, P property. */
    private val SCAN =
        """
        for d in /sys/bus/usb/devices/*; do
          [ -f ${'$'}d/idVendor ] || continue; n=${'$'}{d##*/}
          echo "D|${'$'}n|${'$'}(cat ${'$'}d/idVendor)|${'$'}(cat ${'$'}d/idProduct)|${'$'}(cat ${'$'}d/manufacturer 2>/dev/null)|${'$'}(cat ${'$'}d/product 2>/dev/null)|${'$'}(cat ${'$'}d/bConfigurationValue 2>/dev/null)|${'$'}(cat ${'$'}d/bNumConfigurations 2>/dev/null)|"
          for i in ${'$'}d/${'$'}n:*; do
            [ -f ${'$'}i/bInterfaceClass ] || continue; r=${'$'}(readlink ${'$'}i/driver)
            echo "I|${'$'}n|${'$'}{i##*/}|${'$'}(cat ${'$'}i/bInterfaceClass)|${'$'}(cat ${'$'}i/bInterfaceSubClass)|${'$'}(cat ${'$'}i/bInterfaceProtocol)|${'$'}{r##*/}"
          done
        done
        for n in /sys/class/net/*; do
          m=${'$'}{n##*/}; r=${'$'}(readlink ${'$'}n/device/driver)
          echo "N|${'$'}m|${'$'}(cat ${'$'}n/address 2>/dev/null)|${'$'}(cat ${'$'}n/operstate 2>/dev/null)|${'$'}(cat ${'$'}n/carrier 2>/dev/null)|${'$'}(cat ${'$'}n/speed 2>/dev/null)|${'$'}{r##*/}"
        done
        ip -4 -o addr show 2>/dev/null | while read -r _ dev _ addr _; do echo "A|${'$'}dev|${'$'}addr"; done
        while read -r mod _; do echo "M|${'$'}mod"; done < /proc/modules
        ls $MODULE_DIR 2>/dev/null | sed 's/^/F|/'
        echo "P|off|${'$'}(getprop persist.librehu.usbnet.off)"
        """.trimIndent()
}
