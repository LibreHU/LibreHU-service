package org.librehu.core.bt

/**
 * Reconnection to the last phones, as head units do: after start-up, when Bluetooth comes on or when the phone goes
 * out of range. Attempts are spaced out ([delaysMs], then every [repeatMs]) and alternate between the remembered
 * phones, most recent first. Only phones that were connected before are tried, never every paired device.
 */
class AutoConnectPlan(
    private val delaysMs: List<Long> = listOf(1_000, 4_000, 10_000, 20_000, 30_000, 60_000),
    private val repeatMs: Long = 120_000,
    private val maxAttempts: Int = 20,
) {
    /** Delay before attempt [attempt] (0-based), or null when it is time to give up. */
    fun delayBefore(attempt: Int): Long? =
        when {
            attempt < 0 || attempt >= maxAttempts -> null
            attempt < delaysMs.size -> delaysMs[attempt]
            else -> repeatMs
        }

    /** Phone to try at [attempt]: remembered phones that are still paired, in turn. */
    fun target(
        attempt: Int,
        history: List<String>,
        bonded: Collection<String>,
    ): String? {
        val candidates = history.filter { it in bonded }
        if (candidates.isEmpty()) return null
        return candidates[attempt.coerceAtLeast(0) % candidates.size]
    }
}

/** Most-recently-used list of device addresses (connection history). */
object Mru {
    fun push(
        list: List<String>,
        address: String,
        max: Int = 5,
    ): List<String> = (listOf(address) + list.filter { !it.equals(address, ignoreCase = true) }).take(max)

    fun remove(
        list: List<String>,
        address: String,
    ): List<String> = list.filter { !it.equals(address, ignoreCase = true) }

    fun encode(list: List<String>): String = list.joinToString(",")

    fun decode(value: String?): List<String> =
        value
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
