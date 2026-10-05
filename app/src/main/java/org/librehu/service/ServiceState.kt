package org.librehu.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.unit.VehicleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live state of the service for its own screens (same process). Other apps use the AIDL API and the broadcasts;
 * this only saves the settings app from polling.
 */
object ServiceState {
    data class Link(
        val state: LibreHuService.Link = LibreHuService.Link.STOPPED,
        val detail: String = "",
        val protocol: String = "",
    )

    private val _link = MutableStateFlow(Link())
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleState())
    val vehicle: StateFlow<VehicleState> = _vehicle.asStateFlow()

    private val _traffic = MutableStateFlow<List<String>>(emptyList())

    /** Last MCU frames, newest first (`>` received, `<` sent). */
    val traffic: StateFlow<List<String>> = _traffic.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Last service messages, newest first. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile
    var trafficPaused = false

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun setLink(l: Link) {
        _link.value = l
    }

    fun setVehicle(v: VehicleState) {
        _vehicle.value = v
    }

    fun addTraffic(line: String) {
        if (trafficPaused) return
        _traffic.value = (listOf(stamp(line)) + _traffic.value).take(MAX_LINES)
    }

    fun addLog(line: String) {
        _log.value = (listOf(stamp(line)) + _log.value).take(MAX_LINES)
    }

    fun clearTraffic() {
        _traffic.value = emptyList()
    }

    private fun stamp(line: String) = synchronized(time) { time.format(Date()) } + "  " + line

    private const val MAX_LINES = 300
}
