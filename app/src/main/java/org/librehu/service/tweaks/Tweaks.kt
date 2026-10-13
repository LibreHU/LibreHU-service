package org.librehu.service.tweaks

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell

/** One cpufreq policy (a cluster of cores) as the kernel reports it. */
data class CpuPolicy(
    val id: String,
    val cpus: String,
    val governors: List<String>,
    val governor: String,
    /** Available frequencies in kHz, lowest first (from the table, else the hardware limits). */
    val freqs: List<Int>,
    val minKhz: Int,
    val maxKhz: Int,
    val curKhz: Int,
)

/** What can be read and tuned on the head unit (root). */
data class TweaksState(
    val cpu: List<CpuPolicy> = emptyList(),
    /** GPU operating points in kHz (MediaTek `/proc/gpufreq`), highest first; empty when absent. */
    val gpuOpps: List<Int> = emptyList(),
    /** Frequency the GPU is kept at, 0 = free (DVFS). */
    val gpuKeptKhz: Int = 0,
    val ioDevice: String = "",
    val ioSchedulers: List<String> = emptyList(),
    val ioScheduler: String = "",
    val readAheadKb: Int = 0,
    val swappiness: Int = -1,
    val tcpAvailable: List<String> = emptyList(),
    val tcp: String = "",
    /** Thermal zones: type to °C. */
    val temps: List<Pair<String, Double>> = emptyList(),
)

/** Choices kept and applied again at start-up; null = left as the system sets it. */
data class TweakSettings(
    /** Policy id to governor / min / max (kHz). */
    val governor: Map<String, String> = emptyMap(),
    val minKhz: Map<String, Int> = emptyMap(),
    val maxKhz: Map<String, Int> = emptyMap(),
    /** null = untouched, 0 = free (DVFS), else kept at this kHz. */
    val gpuKhz: Int? = null,
    val ioScheduler: String? = null,
    val readAheadKb: Int? = null,
    val swappiness: Int? = null,
    val tcp: String? = null,
    val applyAtBoot: Boolean = false,
) {
    val empty get() = governor.isEmpty() && minKhz.isEmpty() && maxKhz.isEmpty() && gpuKhz == null && ioScheduler == null &&
        readAheadKb == null && swappiness == null && tcp == null
}

/**
 * Kernel tuning, SmartPack-Kernel style, for the AC8257 kernel (Linux 4.9): CPU governor and frequency limits of each
 * cpufreq policy (interactive, ondemand, conservative, schedutil, powersave, userspace in the stock config), GPU kept
 * at one of MediaTek's operating points or free (`/proc/gpufreq/gpufreq_opp_freq`; the PowerVR GPU has three, no
 * overclock), I/O scheduler and read-ahead of the eMMC, swappiness (zram), TCP congestion control; temperatures.
 * All as root. Choices apply at once and, when asked, again after each start, protected against a start loop: a start
 * that does not get 90 s after applying them skips them the next time ([skippedAtBoot]).
 */
class Tweaks private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)

    private val _state = MutableStateFlow(TweaksState())
    val state: StateFlow<TweaksState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<TweakSettings> = _settings.asStateFlow()

    /** True when the last start did not survive applying the tweaks: they were not applied this time. */
    val skippedAtBoot: Boolean get() = prefs.getBoolean(PREF_SKIPPED, false)

    /** Reads everything (root); blocking. Also keeps the first values seen as the factory ones. */
    fun refresh(): TweaksState? {
        val out = RootShell.run(READ_SCRIPT, timeoutS = 15) ?: return null
        val kv = out.lines().mapNotNull { l -> l.indexOf('|').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }
        val m = kv.toMap()
        val ids = kv.map { it.first }.filter { it.startsWith("cpu.") && it.endsWith(".gov") }.map { it.split('.')[1] }.distinct()
        val cpu =
            ids.map { id ->
                fun v(k: String) = m["cpu.$id.$k"].orEmpty()
                val table = v("freqs").split(' ').mapNotNull { it.toIntOrNull() }.sorted()
                val hw = listOfNotNull(v("hwmin").toIntOrNull(), v("hwmax").toIntOrNull())
                CpuPolicy(
                    id = id,
                    cpus = v("cpus"),
                    governors = v("govs").split(' ').filter { it.isNotBlank() },
                    governor = v("gov"),
                    freqs = table.ifEmpty { hw },
                    minKhz = v("min").toIntOrNull() ?: 0,
                    maxKhz = v("max").toIntOrNull() ?: 0,
                    curKhz = v("cur").toIntOrNull() ?: 0,
                )
            }
        val io = m["io.sched"].orEmpty()
        val state =
            TweaksState(
                cpu = cpu,
                gpuOpps = m["gpu.opps"].orEmpty().split(' ').mapNotNull { it.toIntOrNull() }.distinct().sortedDescending(),
                gpuKeptKhz =
                    m["gpu.keep"].orEmpty().let { k ->
                        if (k.contains("enabled")) Regex("freq = (\\d+)").find(m["gpu.keepfreq"].orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
                    },
                ioDevice = m["io.dev"].orEmpty(),
                ioSchedulers = io.replace("[", "").replace("]", "").split(' ').filter { it.isNotBlank() },
                ioScheduler = Regex("\\[(\\S+)]").find(io)?.groupValues?.get(1).orEmpty(),
                readAheadKb = m["io.ra"]?.toIntOrNull() ?: 0,
                swappiness = m["vm.swappiness"]?.toIntOrNull() ?: -1,
                tcpAvailable = m["tcp.avail"].orEmpty().split(' ').filter { it.isNotBlank() },
                tcp = m["tcp.cur"].orEmpty(),
                temps =
                    kv
                        .filter { it.first.startsWith("temp.") }
                        .mapNotNull { (k, v) ->
                            val raw = v.toDoubleOrNull() ?: return@mapNotNull null
                            k.removePrefix("temp.") to if (raw > 1000) raw / 1000 else raw
                        },
            )
        _state.value = state
        if (!prefs.contains(PREF_FACTORY)) prefs.edit().putString(PREF_FACTORY, toJson(factoryOf(state)).toString()).apply()
        return state
    }

    /** Keeps [transform]'s choices and applies them now (root); blocking. Null when done, else the error. */
    fun update(transform: (TweakSettings) -> TweakSettings): String? {
        val s = transform(_settings.value)
        _settings.value = s
        save(s)
        return apply(s).also { refresh() }
    }

    /** Back to the values read the first time (before any tweak), and nothing applied at start-up any more. */
    fun restoreFactory(): String? {
        val factory = prefs.getString(PREF_FACTORY, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
        val err = factory?.let { apply(it) }
        _settings.value = TweakSettings(applyAtBoot = _settings.value.applyAtBoot)
        save(_settings.value)
        refresh()
        return err
    }

    /** At service start: the kept choices, unless the previous start died right after them. */
    fun applyAtStart() {
        val s = _settings.value
        if (!s.applyAtBoot || s.empty) return
        if (prefs.getBoolean(PREF_PENDING, false)) {
            prefs.edit().putBoolean(PREF_PENDING, false).putBoolean(PREF_SKIPPED, true).commit()
            return
        }
        prefs.edit().putBoolean(PREF_PENDING, true).putBoolean(PREF_SKIPPED, false).commit()
        Thread({ apply(s) }, "tweaks").start()
        Handler(Looper.getMainLooper()).postDelayed({ prefs.edit().putBoolean(PREF_PENDING, false).apply() }, SAFE_MS)
    }

    fun clearSkipped() = prefs.edit().putBoolean(PREF_SKIPPED, false).apply()

    private fun apply(s: TweakSettings): String? {
        val st = _state.value.takeIf { it.cpu.isNotEmpty() } ?: refresh() ?: return "root"
        val cmds = mutableListOf<String>()
        for (p in st.cpu) {
            val base = if (p.id == "legacy") "/sys/devices/system/cpu/cpu0/cpufreq" else "/sys/devices/system/cpu/cpufreq/policy${p.id}"
            s.governor[p.id]?.takeIf { it in p.governors }?.let { cmds += "echo $it > $base/scaling_governor" }
            // Max first when lowering, min first when raising: the kernel refuses min > max.
            val min = s.minKhz[p.id]
            val max = s.maxKhz[p.id]
            if (max != null && max < p.minKhz) {
                min?.let { cmds += "echo $it > $base/scaling_min_freq" }
                cmds += "echo $max > $base/scaling_max_freq"
            } else {
                max?.let { cmds += "echo $it > $base/scaling_max_freq" }
                min?.let { cmds += "echo $it > $base/scaling_min_freq" }
            }
        }
        s.gpuKhz?.takeIf { it == 0 || it in st.gpuOpps }?.let { cmds += "echo $it > /proc/gpufreq/gpufreq_opp_freq" }
        if (st.ioDevice.isNotEmpty()) {
            s.ioScheduler?.takeIf { it in st.ioSchedulers }?.let { cmds += "echo $it > /sys/block/${st.ioDevice}/queue/scheduler" }
            s.readAheadKb?.takeIf { it in 64..4096 }?.let { cmds += "echo $it > /sys/block/${st.ioDevice}/queue/read_ahead_kb" }
        }
        s.swappiness?.takeIf { it in 0..100 }?.let { cmds += "echo $it > /proc/sys/vm/swappiness" }
        s.tcp?.takeIf { it in st.tcpAvailable }?.let { cmds += "echo $it > /proc/sys/net/ipv4/tcp_congestion_control" }
        if (cmds.isEmpty()) return null
        // Each line on its own: one refused value does not stop the others.
        val out = RootShell.run(cmds.joinToString("\n") { "$it 2>&1 || echo 'failed: $it'" }, timeoutS = 15) ?: return "root"
        return out.lines().filter { it.startsWith("failed:") }.joinToString("\n").ifEmpty { null }
    }

    private fun factoryOf(st: TweaksState) =
        TweakSettings(
            governor = st.cpu.associate { it.id to it.governor },
            minKhz = st.cpu.associate { it.id to it.minKhz },
            maxKhz = st.cpu.associate { it.id to it.maxKhz },
            gpuKhz = if (st.gpuOpps.isEmpty()) null else st.gpuKeptKhz,
            ioScheduler = st.ioScheduler.ifEmpty { null },
            readAheadKb = st.readAheadKb.takeIf { it > 0 },
            swappiness = st.swappiness.takeIf { it >= 0 },
            tcp = st.tcp.ifEmpty { null },
        )

    private fun load(): TweakSettings = prefs.getString(PREF, null)?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: TweakSettings()

    private fun save(s: TweakSettings) = prefs.edit().putString(PREF, toJson(s).toString()).apply()

    companion object {
        private const val PREF = "tweaks"
        private const val PREF_FACTORY = "tweaks_factory"
        private const val PREF_PENDING = "tweaks_boot_pending"
        private const val PREF_SKIPPED = "tweaks_boot_skipped"
        private const val SAFE_MS = 90_000L

        /** `key|value` lines; policies by number, `legacy` when the kernel has no policy directories. */
        private val READ_SCRIPT =
            """
            cpu() { b=${'$'}1; n=${'$'}2
              echo "cpu.${'$'}n.cpus|${'$'}(cat ${'$'}b/related_cpus 2>/dev/null || cat ${'$'}b/affected_cpus 2>/dev/null)"
              echo "cpu.${'$'}n.govs|${'$'}(cat ${'$'}b/scaling_available_governors 2>/dev/null)"
              echo "cpu.${'$'}n.gov|${'$'}(cat ${'$'}b/scaling_governor 2>/dev/null)"
              echo "cpu.${'$'}n.freqs|${'$'}(cat ${'$'}b/scaling_available_frequencies 2>/dev/null)"
              echo "cpu.${'$'}n.min|${'$'}(cat ${'$'}b/scaling_min_freq 2>/dev/null)"
              echo "cpu.${'$'}n.max|${'$'}(cat ${'$'}b/scaling_max_freq 2>/dev/null)"
              echo "cpu.${'$'}n.cur|${'$'}(cat ${'$'}b/scaling_cur_freq 2>/dev/null)"
              echo "cpu.${'$'}n.hwmin|${'$'}(cat ${'$'}b/cpuinfo_min_freq 2>/dev/null)"
              echo "cpu.${'$'}n.hwmax|${'$'}(cat ${'$'}b/cpuinfo_max_freq 2>/dev/null)"
            }
            found=0
            for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d "${'$'}p" ] || continue; found=1; cpu "${'$'}p" "${'$'}{p##*policy}"; done
            [ ${'$'}found = 0 ] && [ -d /sys/devices/system/cpu/cpu0/cpufreq ] && cpu /sys/devices/system/cpu/cpu0/cpufreq legacy
            if [ -r /proc/gpufreq/gpufreq_opp_dump ]; then
              echo "gpu.opps|${'$'}(sed -n 's/.*freq = \([0-9]*\),.*/\1/p' /proc/gpufreq/gpufreq_opp_dump | tr '\n' ' ')"
              echo "gpu.keep|${'$'}(head -n1 /proc/gpufreq/gpufreq_opp_freq)"
              echo "gpu.keepfreq|${'$'}(sed -n 2p /proc/gpufreq/gpufreq_opp_freq)"
            fi
            for d in mmcblk0 sda; do
              if [ -r /sys/block/${'$'}d/queue/scheduler ]; then
                echo "io.dev|${'$'}d"
                echo "io.sched|${'$'}(cat /sys/block/${'$'}d/queue/scheduler)"
                echo "io.ra|${'$'}(cat /sys/block/${'$'}d/queue/read_ahead_kb)"
                break
              fi
            done
            echo "vm.swappiness|${'$'}(cat /proc/sys/vm/swappiness 2>/dev/null)"
            echo "tcp.avail|${'$'}(cat /proc/sys/net/ipv4/tcp_available_congestion_control 2>/dev/null)"
            echo "tcp.cur|${'$'}(cat /proc/sys/net/ipv4/tcp_congestion_control 2>/dev/null)"
            for z in /sys/class/thermal/thermal_zone*; do
              [ -r "${'$'}z/temp" ] && echo "temp.${'$'}(cat ${'$'}z/type 2>/dev/null)|${'$'}(cat ${'$'}z/temp 2>/dev/null)"
            done
            true
            """.trimIndent()

        private fun toJson(s: TweakSettings): JSONObject =
            JSONObject()
                .put("governor", JSONObject(s.governor as Map<*, *>))
                .put("min", JSONObject(s.minKhz as Map<*, *>))
                .put("max", JSONObject(s.maxKhz as Map<*, *>))
                .put("gpu", s.gpuKhz ?: -1)
                .put("io", s.ioScheduler.orEmpty())
                .put("ra", s.readAheadKb ?: -1)
                .put("swap", s.swappiness ?: -1)
                .put("tcp", s.tcp.orEmpty())
                .put("boot", s.applyAtBoot)

        private fun fromJson(o: JSONObject): TweakSettings {
            fun strings(name: String): Map<String, String> = o.optJSONObject(name)?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } }.orEmpty()

            fun ints(name: String): Map<String, Int> = o.optJSONObject(name)?.let { j -> j.keys().asSequence().associateWith { j.getInt(it) } }.orEmpty()
            return TweakSettings(
                governor = strings("governor"),
                minKhz = ints("min"),
                maxKhz = ints("max"),
                gpuKhz = o.optInt("gpu", -1).takeIf { it >= 0 },
                ioScheduler = o.optString("io").ifEmpty { null },
                readAheadKb = o.optInt("ra", -1).takeIf { it > 0 },
                swappiness = o.optInt("swap", -1).takeIf { it >= 0 },
                tcp = o.optString("tcp").ifEmpty { null },
                applyAtBoot = o.optBoolean("boot", false),
            )
        }

        @Volatile
        private var instance: Tweaks? = null

        fun get(context: Context): Tweaks = instance ?: synchronized(this) { instance ?: Tweaks(context).also { instance = it } }
    }
}
