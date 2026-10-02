package org.librehu.service.hw

import org.librehu.core.audio.RegisterWriter
import org.librehu.core.mcu.SerialChannel
import org.librehu.core.unit.Gpio
import java.io.IOException

/** JNI bindings of `librehu_hw.c`. Negative results are `-errno`. */
object NativeHw {
    init {
        System.loadLibrary("librehu_hw")
    }

    @JvmStatic external fun serialOpen(
        path: String,
        baud: Int,
    ): Int

    @JvmStatic external fun read(
        fd: Int,
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int

    @JvmStatic external fun write(
        fd: Int,
        data: ByteArray,
    ): Int

    @JvmStatic external fun close(fd: Int)

    @JvmStatic external fun gpioSet(
        gpio: Int,
        high: Boolean,
    ): Int

    @JvmStatic external fun gpioRead(gpio: Int): Int

    @JvmStatic external fun i2cOpen(
        bus: Int,
        address: Int,
    ): Int

    @JvmStatic external fun i2cWriteReg(
        fd: Int,
        address: Int,
        register: Int,
        value: Int,
    ): Int
}

/** MCU serial port (`/dev/ttyS1`, 115200 8N1). */
class TtySerialChannel private constructor(
    private val fd: Int,
) : SerialChannel {
    @Volatile
    private var closed = false

    override fun read(
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int {
        if (closed) return -1
        val n = NativeHw.read(fd, buffer, timeoutMs)
        if (n < 0) throw IOException("read: errno ${-n}")
        return n
    }

    override fun write(bytes: ByteArray) {
        val n = NativeHw.write(fd, bytes)
        if (n < 0) throw IOException("write: errno ${-n}")
    }

    override fun close() {
        if (!closed) {
            closed = true
            NativeHw.close(fd)
        }
    }

    companion object {
        const val MCU_PORT = "/dev/ttyS1"
        const val MCU_BAUD = 115200

        fun open(
            path: String = MCU_PORT,
            baud: Int = MCU_BAUD,
        ): TtySerialChannel {
            val fd = NativeHw.serialOpen(path, baud)
            if (fd < 0) throw IOException("open $path: errno ${-fd}")
            return TtySerialChannel(fd)
        }
    }
}

/** SoC GPIOs through `/dev/gpios_ioctl`. */
object SocGpio : Gpio {
    override fun set(
        gpio: Int,
        high: Boolean,
    ): Boolean = NativeHw.gpioSet(gpio, high) == 0

    override fun read(gpio: Int): Int = NativeHw.gpioRead(gpio).let { if (it < 0) -1 else it }
}

/** One I2C device. */
class I2cDevice private constructor(
    private val fd: Int,
    private val address: Int,
) : RegisterWriter {
    override fun write(
        register: Int,
        value: Int,
    ): Boolean = NativeHw.i2cWriteReg(fd, address, register, value) == 0

    companion object {
        fun open(
            bus: Int,
            address: Int,
        ): I2cDevice {
            val fd = NativeHw.i2cOpen(bus, address)
            if (fd < 0) throw IOException("open /dev/i2c-$bus @0x${address.toString(16)}: errno ${-fd}")
            return I2cDevice(fd, address)
        }
    }
}
