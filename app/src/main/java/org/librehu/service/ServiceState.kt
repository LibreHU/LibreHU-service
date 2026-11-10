package org.librehu.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.librehu.core.mcu.McuDebugState
import org.librehu.core.mcu.McuFrame
import org.librehu.core.unit.VehicleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live state of the service for its own screens (same process). Other apps use the AIDL API and the broadcasts;
 * this only saves the settings app from polling.
 */
object ServiceState {
    /** Audio chip driven by the running link (registers last written, for the audio diagnostics). */
    @Volatile
    var dsp: org.librehu.core.audio.Bd37534? = null

    data class Link(
        val state: LibreHuService.Link = LibreHuService.Link.STOPPED,
        val detail: String = "",
        val protocol: String = "",
        /** Jancar `JAC_V1`: the MCU debug tools (descriptions, commands, simulation) apply. */
        val jancar: Boolean = false,
    )

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleState())
    val vehicle: StateFlow<VehicleState> = _vehicle.asStateFlow()

    private val _traffic = MutableStateFlow<List<String>>(emptyList())

    /** Last MCU frames, newest first (`>` received, `<` sent, `~ SIM` simulated). */
    val traffic: StateFlow<List<String>> = _traffic.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Last service messages, newest first. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _mcu = MutableStateFlow(McuDebugState())

    /** What the MCU said (debug tiles). */
    val mcu: StateFlow<McuDebugState> = _mcu.asStateFlow()

    @Volatile
    var trafficPaused = false

    /** Leaves the MCU acknowledgements out of the traffic. */
    @Volatile
    var hideAcks = false

    /** Handles a frame as if the MCU had sent it, while the link runs (set by the service). */
    @Volatile
    var simulator: ((McuFrame) -> Unit)? = null

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun setLink(l: Link) {
        _link.value = l
    }

    fun setVehicle(v: VehicleState) {
        _vehicle.value = v
    }

    /** The last [DIAG_LINES] MCU frames, for LibreHU Helper's report (DiagnosticsProvider), oldest first. */
    private val trafficRing = ArrayDeque<String>()

    fun addTraffic(line: String) {
        val l = stamp(line)
        synchronized(trafficRing) {
            trafficRing.addLast(l)
            if (trafficRing.size > DIAG_LINES) trafficRing.removeFirst()
        }
        if (trafficPaused) return
        // update: several threads add lines (MCU reader, head unit's thread, CAN box link): none is lost.
        _traffic.update { (listOf(l) + it).take(MAX_LINES) }
    }

    /** A line of the service's log: on its screens, and in its file (diag/ServiceLog, read by LibreHU Helper). */
    fun addLog(line: String) {
        org.librehu.service.diag.ServiceLog
            .add(line)
        val l = stamp(line)
        _log.update { (listOf(l) + it).take(MAX_LINES) }
    }

    /** The last MCU frames (received `>`, sent `<`), oldest first, paused or not. */
    fun recentTraffic(): List<String> = synchronized(trafficRing) { trafficRing.toList() }

    fun clearTraffic() {
        _traffic.value = emptyList()
    }

    fun updateMcu(change: (McuDebugState) -> McuDebugState) {
        synchronized(_mcu) { _mcu.value = change(_mcu.value) }
    }

    fun resetMcu() {
        synchronized(_mcu) { _mcu.value = McuDebugState() }
    }

    /** Traffic and service log, oldest first, for an export file. */
    fun dump(): String =
        buildString {
            append("# LibreHU service — ").append(link.value.protocol).append('\n')
            append("# ").append(mcu.value).append("\n\n## MCU traffic\n")
            _traffic.value.asReversed().forEach { append(it).append('\n') }
            append("\n## Service log\n")
            _log.value.asReversed().forEach { append(it).append('\n') }
        }

    private fun stamp(line: String) = synchronized(time) { time.format(Date()) } + "  " + line

    private const val MAX_LINES = 300
    private const val DIAG_LINES = 2_000
}
