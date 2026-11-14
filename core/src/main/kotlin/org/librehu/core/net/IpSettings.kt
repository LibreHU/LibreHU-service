package org.librehu.core.net

/**
 * IP settings of a wired interface that Android's Ethernet service manages (USB Ethernet adapter, Android phone's
 * RNDIS tethering, iPhone tethering): DHCP, or a static IPv4 address with an optional gateway and DNS servers.
 *
 * One line for the preferences and the root helper: `dhcp`, or `static;<address>/<prefix>;<gateway>;<dns>,<dns>`
 * (gateway and DNS may be empty).
 */
data class IpSettings(
    val static: Boolean = false,
    val address: String = "",
    val prefix: Int = 24,
    val gateway: String = "",
    val dns: List<String> = emptyList(),
) {
    enum class Problem { ADDRESS, PREFIX, GATEWAY, GATEWAY_OUTSIDE, DNS }

    /** What prevents applying these settings; always empty for DHCP. */
    fun problems(): List<Problem> {
        if (!static) return emptyList()
        val out = ArrayList<Problem>()
        val addressOk = isIpv4(address)
        if (!addressOk) out += Problem.ADDRESS
        val prefixOk = prefix in 1..32
        if (!prefixOk) out += Problem.PREFIX
        if (gateway.isNotEmpty()) {
            when {
                !isIpv4(gateway) -> out += Problem.GATEWAY
                addressOk && prefixOk && !sameSubnet(address, gateway, prefix) -> out += Problem.GATEWAY_OUTSIDE
            }
        }
        if (dns.size > MAX_DNS || dns.any { !isIpv4(it) }) out += Problem.DNS
        return out
    }

    fun encode(): String = if (!static) DHCP else listOf(STATIC, "$address/$prefix", gateway, dns.joinToString(",")).joinToString(";")

    companion object {
        const val DHCP = "dhcp"
        private const val STATIC = "static"
        const val MAX_DNS = 2

        /** The settings of [encode]; null when the line is not one. */
        fun decode(line: String?): IpSettings? {
            val s = line?.trim() ?: return null
            if (s == DHCP) return IpSettings()
            val f = s.split(';')
            if (f.size != 4 || f[0] != STATIC) return null
            val (address, prefix) = f[1].split('/').takeIf { it.size == 2 } ?: return null
            return IpSettings(
                static = true,
                address = address,
                prefix = prefix.toIntOrNull() ?: return null,
                gateway = f[2],
                dns = f[3].split(',').map { it.trim() }.filter { it.isNotEmpty() },
            )
        }

        /** A dotted IPv4 address: four numbers 0 to 255, digits only. */
        fun isIpv4(s: String): Boolean {
            val parts = s.split('.')
            return parts.size == 4 &&
                parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
        }

        /** Whether [a] and [b] (valid IPv4 addresses) are in the same /[prefix] network. */
        fun sameSubnet(
            a: String,
            b: String,
            prefix: Int,
        ): Boolean {
            val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL
            return (toLong(a) and mask) == (toLong(b) and mask)
        }

        private fun toLong(ip: String): Long = ip.split('.').fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
    }
}
