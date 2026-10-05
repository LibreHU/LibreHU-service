package org.librehu.core.mcu

/** Decodes a byte stream into [McuFrame]s (one instance per link). */
interface FrameParser {
    val errors: Int

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    )

    fun reset()
}

/** Serial port of the MCU. */
data class SerialConfig(
    val port: String = "/dev/ttyS1",
    val baud: Int = 115200,
)

/**
 * The MCU protocol of a head unit model: framing, acknowledgements, what received frames mean and the frames that
 * drive the hardware. [JacProtocol] is the native Jancar one; [ProfileProtocol] reads any other from a JSON profile
 * ([McuProfiles]). A null frame means "not supported by this MCU".
 */
interface McuProtocol {
    val id: String
    val name: String
    val serial: SerialConfig
    val ackTimeoutMs: Long
    val maxTries: Int

    fun newParser(onFrame: (McuFrame) -> Unit): FrameParser

    fun encode(frame: McuFrame): ByteArray

    /** Command acknowledged by [frame], or null when it is not an acknowledgement. */
    fun ackedCommand(frame: McuFrame): Int?

    fun needsAck(frame: McuFrame): Boolean

    fun decode(frame: McuFrame): McuEvent

    fun pcReady(): McuFrame?

    fun mute(on: Boolean): McuFrame?

    fun externalAmp(on: Boolean): McuFrame?

    fun antenna(on: Boolean): McuFrame?

    fun date(
        year: Int,
        month: Int,
        day: Int,
    ): McuFrame?

    fun time(
        hour: Int,
        minute: Int,
        second: Int,
    ): McuFrame?

    /** Raw bytes for the CAN box, when the MCU relays them. */
    fun canData(bytes: ByteArray): McuFrame?
}

/** Jancar `JAC_V1` (UJC201 / AC8257), implemented natively: [JacFrame], [JacParser], [Mcu], [McuEvent.decode]. */
object JacProtocol : McuProtocol {
    override val id = "jancar-jac-v1"
    override val name = "Jancar JAC_V1 (UJC201, AC8257)"
    override val serial = SerialConfig("/dev/ttyS1", 115200)
    override val ackTimeoutMs = 500L
    override val maxTries = 5

    override fun newParser(onFrame: (McuFrame) -> Unit): FrameParser {
        val p = JacParser(onFrame)
        return object : FrameParser {
            override val errors get() = p.errors

            override fun feed(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) = p.feed(bytes, offset, length)

            override fun reset() = p.reset()
        }
    }

    override fun encode(frame: McuFrame) = JacFrame.encode(frame.cmd, frame.data)

    override fun ackedCommand(frame: McuFrame) = if (frame.cmd == JacFrame.CMD_ACK && frame.data.isNotEmpty()) frame.u(0) else null

    override fun needsAck(frame: McuFrame) = frame.cmd in Mcu.NEEDS_ACK

    override fun decode(frame: McuFrame) = McuEvent.decode(frame)

    override fun pcReady() = Mcu.pcReady()

    override fun mute(on: Boolean) = Mcu.mute(on)

    override fun externalAmp(on: Boolean) = Mcu.externalAmp(on)

    override fun antenna(on: Boolean) = Mcu.antenna(on)

    override fun date(
        year: Int,
        month: Int,
        day: Int,
    ) = Mcu.date(year, month, day)

    override fun time(
        hour: Int,
        minute: Int,
        second: Int,
    ) = Mcu.time(hour, minute, second)

    override fun canData(bytes: ByteArray) = Mcu.canData(bytes)
}
