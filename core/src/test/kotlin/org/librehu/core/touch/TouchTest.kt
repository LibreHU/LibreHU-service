package org.librehu.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchTest {
    @Test
    fun affineFitAndInverse() {
        val t = Affine(1.2, 0.1, -30.0, -0.05, 0.9, 12.0)
        val from = listOf(0.0 to 0.0, 100.0 to 0.0, 0.0 to 50.0, 80.0 to 70.0, 33.0 to 11.0)
        val to = from.map { (x, y) -> t.map(x, y) }
        val fit = Affine.fit(from, to)
        assertEquals(t.a, fit.a, 1e-9)
        assertEquals(t.f, fit.f, 1e-9)
        val back = fit.inverse().map(t.map(42.0, 17.0).first, t.map(42.0, 17.0).second)
        assertEquals(42.0, back.first, 1e-9)
        assertEquals(17.0, back.second, 1e-9)
    }

    /**
     * Synthetic panel: raw coordinates are skewed and offset, Android rotates the driver coordinates by 90° (as
     * persist.sf.hwrotation = 90) and scales them. The solver must find the matrix that puts every touch on its
     * target, starting from a wrong current matrix.
     */
    @Test
    fun calibrationRecoversTheRightMatrixWhateverTheRotation() {
        val screenW = 1024
        val screenH = 600
        // Android: driver (portrait 600 x 1024 units) → screen, rotated 90°.
        val androidMap = Affine(0.0, 1.0, 0.0, -1.0, 0.0, 600.0)
        // Truth: where the finger really is, in raw panel units, for a screen point.
        val screenToRaw = Affine(0.0, -1.05, 640.0, 0.98, 0.02, -15.0)
        val current = Gt9xxMatrix.of(Affine(1.0, 0.03, 12.0, -0.02, 1.0, -20.0)) // a bad old calibration
        val samples =
            TouchCalibration.targets(screenW, screenH).map { (tx, ty) ->
                val (rx, ry) = screenToRaw.map(tx, ty)
                val (kx, ky) = current.toAffine().map(rx, ry)
                val (sx, sy) = androidMap.map(kx, ky)
                CalibrationSample(kx, ky, sx, sy, tx, ty)
            }
        val result = TouchCalibration.solve(current, samples)
        assertTrue("error ${result.maxErrorPx}", result.maxErrorPx < 1.0)
        // A point not used for the calibration lands where it should.
        val (rx, ry) = screenToRaw.map(300.0, 450.0)
        val (kx, ky) = result.matrix.toAffine().map(rx, ry)
        val (sx, sy) = androidMap.map(kx, ky)
        assertEquals(300.0, sx, 1.0)
        assertEquals(450.0, sy, 1.0)
        assertEquals(Gt9xxMatrix.DIV, result.matrix.div)
    }

    @Test
    fun gt9xxText() {
        assertEquals("1 0 0 0 1 0 1", Gt9xxMatrix.IDENTITY.toString())
        assertEquals(Gt9xxMatrix(65536, 0, -100, 3, 65000, 7, 65536), Gt9xxMatrix.parse(" 65536 0 -100 3 65000 7 65536\n"))
        assertNull(Gt9xxMatrix.parse("1 2 3"))
        assertNull(Gt9xxMatrix.parse("1 0 0 0 1 0 0"))
    }

    @Test
    fun pointercalKeepsOtherPanels() {
        val jancar =
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <config>
            <node touch="9147_0_1024_600_ABC">
            <item id="A">1</item>
            <item id="B">0</item>
            <item id="C">0</item>
            <item id="D">0</item>
            <item id="E">1</item>
            <item id="F">0</item>
            <item id="Div">1</item>
            </node>
            </config>
            """.trimIndent()
        assertEquals(Gt9xxMatrix.IDENTITY, Pointercal.parse(jancar)["9147_0_1024_600_ABC"])
        val m = Gt9xxMatrix(65000, 10, 20, 30, 66000, 40, 65536)
        val updated = Pointercal.parse(Pointercal.upsert(jancar, "OTHER", m))
        assertEquals(Gt9xxMatrix.IDENTITY, updated["9147_0_1024_600_ABC"])
        assertEquals(m, updated["OTHER"])
    }

    private fun event(
        type: Int,
        code: Int,
        value: Int,
        sec: Long = 1,
        usec: Long = 0,
    ): ByteArray =
        ByteBuffer
            .allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sec)
            .putLong(usec)
            .putShort(type.toShort())
            .putShort(code.toShort())
            .putInt(value)
            .array()

    @Test
    fun inputEventsGiveSamples() {
        val got = mutableListOf<TouchSample>()
        val p = InputEventParser { got += it }
        val stream =
            event(3, 0x39, 7) + event(3, 0x35, 1080) + event(3, 0x36, 20) + event(1, 0x14A, 1) + event(0, 0, 0, usec = 5000) +
                event(3, 0x35, 1082) + event(0, 0, 0, usec = 9000) +
                event(3, 0x39, -1) + event(1, 0x14A, 0) + event(0, 0, 0, sec = 2)
        // Fed in odd chunks: records cut in the middle must still decode.
        stream.toList().chunked(7).forEach { p.feed(it.toByteArray()) }
        assertEquals(3, got.size)
        assertEquals(TouchSample(true, 1080, 20, 1005), got[0])
        assertEquals(1082, got[1].x)
        assertEquals(false, got[2].down)
    }

    @Test
    fun handlerOfTheTouchPanel() {
        val devices =
            """
            I: Bus=0019 Vendor=2454 Product=6500 Version=0010
            N: Name="mtk-kpd"
            H: Handlers=event0

            I: Bus=0000 Vendor=0000 Product=0911 Version=1060
            N: Name="mtk-tpd"
            S: Sysfs=/devices/virtual/input/input2
            H: Handlers=event2
            B: PROP=2
            """.trimIndent()
        assertEquals("event2", InputEventParser.findHandler(devices, "mtk-tpd"))
        assertNull(InputEventParser.findHandler(devices, "gt9xx"))
    }

    @Test
    fun zonesClickLongPressAndRepeat() {
        val actions = mutableListOf<String>()
        val home = TouchZone(1, "Home", 1050, 100, 30, click = ZoneAction(TouchAction.HOME), longPress = ZoneAction(TouchAction.SCREEN_OFF))
        val volUp = TouchZone(2, "Vol+", 1050, 300, 30, click = ZoneAction(TouchAction.VOLUME_UP), repeat = true)
        val e = TouchZoneEngine(listOf(home, volUp)) { z, a -> actions += "${z.name}:${a.action}" }
        // Short press on Home.
        e.onSample(TouchSample(true, 1052, 98, 0))
        e.onSample(TouchSample(false, 1052, 98, 200))
        // Long press on Home: long action once, no click on release.
        e.onSample(TouchSample(true, 1050, 100, 1000))
        e.onTick(1700)
        e.onTick(1900)
        e.onSample(TouchSample(false, 1050, 100, 2000))
        // Held Vol+: repeats.
        e.onSample(TouchSample(true, 1050, 300, 3000))
        e.onTick(3600)
        e.onTick(3760)
        e.onTick(3800)
        e.onSample(TouchSample(false, 1050, 300, 3850))
        // Outside every zone: nothing.
        e.onSample(TouchSample(true, 500, 300, 5000))
        e.onSample(TouchSample(false, 500, 300, 5100))
        assertEquals(listOf("Home:HOME", "Home:SCREEN_OFF", "Vol+:VOLUME_UP", "Vol+:VOLUME_UP"), actions)
    }

    @Test
    fun zonesJson() {
        val zones =
            listOf(
                TouchZone(
                    3,
                    "Nav",
                    10,
                    20,
                    35,
                    ZoneAction(TouchAction.LAUNCH_APP, "com.waze"),
                    ZoneAction(TouchAction.KEYCODE, "84"),
                    false,
                ),
            )
        assertEquals(zones, TouchZoneEngine.fromJson(TouchZoneEngine.toJson(zones)))
        assertEquals(emptyList<TouchZone>(), TouchZoneEngine.fromJson("garbage"))
    }
}
