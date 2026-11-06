package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanBoxLinkTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private class Sent(
        val bytes: String,
        val what: String,
    )

    private fun link(
        l: CanLink,
        out: MutableList<Sent>,
        acked: MutableList<Int> = mutableListOf(),
    ) = CanBoxLink(CanBoxFamilies.HIWORLD, l, { b, w -> out += Sent(hex(b), w) }, { acked += it })

    private fun frame(
        cmd: Int,
        vararg data: Int,
        ok: Boolean = true,
    ) = CanboxFrame(cmd, ByteArray(data.size) { data[it].toByte() }, ok)

    @Test
    fun hiworldAcknowledgesEveryMessageButAcks() {
        val out = mutableListOf<Sent>()
        val acked = mutableListOf<Int>()
        val l = link(CanLink(), out, acked)
        l.onFrame(frame(0x11, 0x61, 0, 0, 0, 8, 0, 0, 0, 2, 0x17), 0)
        // As Hiworld's app: 5A A5 01 FF <cmd> CS, CS = ((1 + 0xFF + cmd) & 0xFF) - 1.
        assertEquals("5A A5 01 FF 11 10", out.single().bytes)
        l.onFrame(frame(0xFF, 0x24), 10)
        l.onFrame(frame(0xFE, 0x24), 20)
        l.onFrame(frame(0x12, 0, ok = false), 30)
        assertEquals(1, out.size)
        assertEquals(listOf(0x24, 0x24), acked)
    }

    @Test
    fun clio3CarTypeAtOneSecondThenEveryThirty() {
        val out = mutableListOf<Sent>()
        val l = link(CanVehicles.RENAULT_CLIO3_HIWORLD.link.copy(ack = false), out)
        l.start(0)
        l.tick(999)
        assertTrue(out.isEmpty())
        l.tick(1_000)
        // HdRenaultProtocolLNP002.buildCarTypePacket: getPacket(0x24, [car code 0, 0x31]).
        assertEquals("5A A5 02 24 00 31 56", out.single().bytes)
        l.tick(30_999)
        assertEquals(1, out.count { it.what == "car type" })
        l.tick(31_000)
        assertEquals(2, out.count { it.what == "car type" })
    }

    @Test
    fun clio3SourceAndBluetoothForTheCarDisplay() {
        val out = mutableListOf<Sent>()
        val l = link(CanVehicles.RENAULT_CLIO3_HIWORLD.link.copy(ack = false), out)
        l.start(0)
        l.tick(2_000)
        // HdRenaultProtocolLNP002.getSource (source 3: [10, 12 spaces], 0xE1) and getBtInfo (6 = not connected, 0xCD).
        assertEquals("5A A5 0D E1 0A 20 20 20 20 20 20 20 20 20 20 20 20 77", out.single { it.what == "source" }.bytes)
        assertEquals("5A A5 1B CD 06" + " 00".repeat(26) + " ED", out.single { it.what == "bluetooth" }.bytes)
        l.tick(12_000)
        assertEquals(2, out.count { it.what == "source" })
        assertEquals(1, out.count { it.what == "bluetooth" })
    }

    @Test
    fun clio3AsksTheBoxItsVersion() {
        val out = mutableListOf<Sent>()
        val l = link(CanVehicles.RENAULT_CLIO3_HIWORLD.link.copy(ack = false), out)
        l.start(0)
        l.tick(3_000)
        // Hiworld request (HdProtocol.txRequestCmdId 0x6A, data [5, 1, cmd]): the box sends message F0 again.
        assertEquals("5A A5 03 6A 05 01 F0 62", out.single { it.what == "version request" }.bytes)
    }

    @Test
    fun boxBackAfterSilenceGetsTheMessagesAgain() {
        val out = mutableListOf<Sent>()
        val once = CanLink(ack = false, send = listOf(CanSend("init", 0x24, listOf(0, 0x31), firstMs = 100, everyMs = 0)))
        val l = link(once, out)
        l.start(0)
        l.onFrame(frame(0x11, 0), 50)
        l.tick(100)
        l.tick(10_000)
        assertEquals(1, out.size)
        // Six seconds without a message, then the box talks again: restarted.
        l.onFrame(frame(0x11, 0), 6_200)
        l.tick(6_299)
        assertEquals(1, out.size)
        l.tick(6_300)
        assertEquals(2, out.size)
    }

    @Test
    fun linkSurvivesTheJsonRoundTrip() {
        val v = CanVehicles.RENAULT_CLIO3_HIWORLD
        val again = CanVehicles.fromJson(CanVehicles.toJson(v))
        assertEquals(v.link, again.link)
        assertEquals(listOf(0x00, 0x31), again.link.send.single { it.name == "car type" }.data)
        assertEquals(30_000L, again.link.send.single { it.name == "car type" }.everyMs)
    }

    @Test(expected = CanVehicleException::class)
    fun unknownBoxRefused() {
        CanVehicles.fromJson(
            """{"format":"librehu-can-vehicle","version":1,"id":"x-car","box":"nope","messages":{}}""",
        )
    }
}

class CanBoxFamiliesTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun roundTrip(
        family: CanBoxFamily,
        cmd: Int,
        data: ByteArray,
    ): CanboxFrame {
        val got = mutableListOf<CanboxFrame>()
        val p = family.parser { got += it }
        val bytes = family.encode(cmd, data)
        // Noise first, then the message cut in two chunks, as the MCU relays it.
        p.feed(byteArrayOf(0x00, 0x13))
        p.feed(bytes.copyOfRange(0, 3))
        p.feed(bytes.copyOfRange(3, bytes.size))
        return got.single()
    }

    @Test
    fun raiseFraming() {
        // 2E CMD LEN D.. CS, CS = (CMD + LEN + ΣD) XOR 0xFF (ReBaseProtocol.getPacket / calculateCheckSum).
        assertEquals("2E 20 02 01 01 DB", hex(CanBoxFamilies.RAISE.encode(0x20, byteArrayOf(1, 1))))
        val f = roundTrip(CanBoxFamilies.RAISE, 0x20, byteArrayOf(1, 1))
        assertEquals(0x20, f.cmd)
        assertTrue(f.checksumOk)
        assertEquals("FF", hex(CanBoxFamilies.RAISE.ack(f)!!))
        // The box's single-byte answers are not messages.
        val got = mutableListOf<CanboxFrame>()
        val p = RaiseParser { got += it }
        p.feed(byteArrayOf(0xFF.toByte(), 0xF0.toByte()))
        assertTrue(got.isEmpty())
        assertEquals(2L, p.answers)
        assertEquals(0L, p.skipped)
    }

    @Test
    fun hiworldAa55AndFdFramings() {
        assertEquals("AA 55 02 24 00 31 56", hex(CanBoxFamilies.HIWORLD_AA55.encode(0x24, byteArrayOf(0, 0x31))))
        assertTrue(roundTrip(CanBoxFamilies.HIWORLD_AA55, 0x24, byteArrayOf(0, 0x31)).checksumOk)
        assertEquals(null, CanBoxFamilies.HIWORLD_AA55.ack(CanboxFrame(0x11, byteArrayOf(0), true)))
        // FD LEN CMD D.. CS: LEN = n + 3, CS = (LEN + CMD + ΣD) & 0xFF (ReFDProtocol).
        assertEquals("FD 05 10 01 02 18", hex(CanBoxFamilies.RAISE_FD.encode(0x10, byteArrayOf(1, 2))))
        assertTrue(roundTrip(CanBoxFamilies.RAISE_FD, 0x10, byteArrayOf(1, 2)).checksumOk)
        // FD LEN CMD D.. CSH CSL: LEN = n + 4, 16-bit sum (ReFD2Protocol).
        assertEquals("FD 06 10 01 02 00 19", hex(CanBoxFamilies.RAISE_FD2.encode(0x10, byteArrayOf(1, 2))))
        val f = roundTrip(CanBoxFamilies.RAISE_FD2, 0x10, byteArrayOf(1, 2))
        assertTrue(f.checksumOk)
        assertEquals(listOf(1, 2), f.data.map { it.toInt() })
    }

    @Test
    fun makersNameTheirFraming() {
        assertTrue(CanBoxFamilies.byId("simple") === CanBoxFamilies.RAISE)
        assertTrue(CanBoxFamilies.byId("hiworld") === CanBoxFamilies.HIWORLD)
        assertEquals(null, CanBoxFamilies.byId("klyde"))
    }

    @Test
    fun wheelKeysFromTheProfile() {
        val v =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":1,"id":"raise-car","box":"raise",
                   "wheel_keys":{"cmd":"0x20","code_byte":0,"state_byte":1,"pressed":[1,2],"names":{"1":"volume +"}},"messages":{}}""",
            )
        val spec = v.keys!!
        assertEquals("volume +", spec.name(1))
        val got = mutableListOf<Int>()
        val k = HiworldWheelKeys(spec = spec) { got += it }
        listOf(byteArrayOf(1, 1), byteArrayOf(1, 2), byteArrayOf(1, 0), byteArrayOf(0, 0), byteArrayOf(3, 1)).forEach {
            k.onFrame(CanboxFrame(0x20, it, true))
        }
        assertEquals(listOf(1, 3), got)
        // Written back as it was read.
        assertEquals(spec, CanVehicles.fromJson(CanVehicles.toJson(v)).keys)
        // The Clio 3 profile states its keys; an old Hiworld profile with only the 0x11 wheel_key signal gets LNP002's.
        assertEquals(0x08, CanVehicles.RENAULT_CLIO3_HIWORLD.keys!!.eventMask)
    }
}

class TwrpCanConfTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun clio3() {
        val c = TwrpCanConf.of(CanVehicles.RENAULT_CLIO3_HIWORLD)
        assertTrue(c.contains("\nframe 5A A5\norder lc\nlenoff 0\nsum sum 1 -1 00 lcd\nackcmd FF\nnoack FE FF\nbaud 38400\n"))
        assertTrue(c.contains("\nack 1\n"))
        assertTrue(c.contains("\nsend 1000 30000 5A A5 02 24 00 31 56\n"))
        assertTrue(c.contains("\nsend 1500 10000 5A A5 0D E1 0A 20 20 20 20 20 20 20 20 20 20 20 20 77\n"))
        assertTrue(c.contains("\nkeys 11 02 03 01 01 08\n"))
        // Key names of the profile -> TWRP actions (the table TWRP had built in).
        assertTrue(c.contains("\nkey 01 volup volume_+\n"))
        assertTrue(c.contains("\nkey 05 up previous\n"))
        assertTrue(c.contains("\nkey 0A back mode\n"))
        assertTrue(c.contains("\nkey 14 back hang_up\n"))
        assertTrue(!c.contains("voice"))
    }

    @Test
    fun raiseBoxAcknowledgedAndKeysInTwrp() {
        val v =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":1,"id":"raise-car","box":"raise",
                   "link":{"send":[{"cmd":"0x81","data":"01","every_ms":0}]},
                   "wheel_keys":{"cmd":"0x20","code_byte":0,"state_byte":1},"messages":{}}""",
            )
        val c = TwrpCanConf.of(v)
        assertTrue(c.contains("\nframe 2E\norder cl\nlenoff 0\nsum sum 1 0 FF lcd\nackraw FF\nnoack\n"))
        assertTrue(c.contains("\nack 1\n"))
        assertTrue(c.contains("\nsend 1000 0 2E 81 01 01 7C\n"))
        assertTrue(c.contains("\nkeys 20 00 01 01 FF FF\n"))
    }

    @Test
    fun builtInFamiliesReadTheSameInTwrp() {
        val data = byteArrayOf(0x01, 0x7F, 0xFF.toByte(), 0x00, 0x42)
        for (family in CanBoxFamilies.ALL) {
            val f = TwrpCanConf.framing(family.id)!!
            for (n in 0..data.size) {
                val d = data.copyOf(n)
                assertEquals(family.id, hex(family.encode(0x11, d)), hex(f.encode(0x11, d)))
            }
            val frame = CanboxFrame(0x11, byteArrayOf(0x08, 0x01), true)
            val ack = family.ack(frame)
            val twrp =
                when {
                    0x11 in f.noAckFor -> null
                    f.ackRaw != null -> ByteArray(f.ackRaw!!.size) { f.ackRaw!![it].toByte() }
                    f.ackCmd != null -> f.encode(f.ackCmd!!, byteArrayOf(0x11))
                    else -> null
                }
            assertEquals(family.id, ack?.let(::hex), twrp?.let(::hex))
        }
    }

    @Test
    fun keyNamesToTwrpActions() {
        assertEquals("volup", TwrpCanConf.action("volume +"))
        assertEquals("up", TwrpCanConf.action("previous / answer"))
        assertEquals("enter", TwrpCanConf.action("mute (hold: power)"))
        assertEquals("back", TwrpCanConf.action("hang up / back"))
        assertEquals("home", TwrpCanConf.action("navigation"))
        assertEquals(null, TwrpCanConf.action("voice"))
    }
}

class CanVariantsTest {
    @Test
    fun lnp002CarsSetTheCarType() {
        val p = CanVehicles.RENAULT_CLIO3_HIWORLD
        assertEquals(94, p.variants.size)
        // Default and Clio 3: car code 0 (HdRenaultProtocolLNP002.buildCarTypePacket, renault.xml).
        assertEquals(listOf(0x00, 0x31), p.link.send.single { it.name == "car type" }.data)
        assertEquals(listOf(0x00, 0x31), p.withVariant("Renault Clio3 2005-2014, all trims").link.send.single { it.name == "car type" }.data)
        // LADA Priora: series 112, model 2 → 34 (0x22).
        assertEquals(listOf(0x22, 0x31), p.withVariant("LADA priora 2012-now, all trims").link.send.single { it.name == "car type" }.data)
        // Unknown name: unchanged.
        assertEquals(p.link, p.withVariant("nope").link)
        // Through JSON and TWRP's file.
        val again = CanVehicles.fromJson(CanVehicles.toJson(p))
        assertEquals(p.variants, again.variants)
        assertTrue(TwrpCanConf.of(p.withVariant("LADA priora 2012-now, all trims")).contains("send 1000 30000 5A A5 02 24 22 31 78"))
    }
}

class Batch1ProfilesTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun builtInsLoad() {
        val ids = CanVehicles.BUILT_IN.map { it.id }
        assertEquals(listOf("renault-clio3-hiworld", "hyundai-kia-hiworld", "toyota-hiworld", "honda-hiworld", "vw-mqb-hiworld", "vw-pq-hiworld", "vw-mqb-raise", "vw-pq-raise", "psa-raise", "psa-raise-keep", "hiworld-generic"), ids)
        assertEquals(94, CanVehicles.RENAULT_CLIO3_HIWORLD.variants.size)
        assertEquals(152, CanVehicles.HYUNDAI_KIA_HIWORLD.variants.size)
        assertEquals(142, CanVehicles.TOYOTA_HIWORLD.variants.size)
        assertEquals(79, CanVehicles.HONDA_HIWORLD.variants.size)
        // HdKoreaProtocol.acknowledge() is null; Toyota and Honda keep Hiworld's acknowledgement.
        assertEquals(false, CanVehicles.HYUNDAI_KIA_HIWORLD.link.ack)
        assertEquals(true, CanVehicles.TOYOTA_HIWORLD.link.ack)
        assertEquals(true, CanVehicles.HONDA_HIWORLD.link.ack)
    }

    @Test
    fun noCarTypeBeforeACarIsChosen() {
        val out = mutableListOf<String>()
        val l = CanBoxLink(CanBoxFamilies.HIWORLD, CanVehicles.TOYOTA_HIWORLD.link.copy(ack = false), { b, _ -> out += hex(b) })
        l.start(0)
        l.tick(60_000)
        assertTrue(out.isEmpty())
        assertTrue(!TwrpCanConf.of(CanVehicles.TOYOTA_HIWORLD).contains("\nsend "))
        // A car chosen: its car type, as its decoder builds it (getPacket(0x24, [code, 0x01])).
        val v = CanVehicles.TOYOTA_HIWORLD.variants.first()
        val withCar = CanVehicles.TOYOTA_HIWORLD.withVariant(v.name)
        assertEquals(0x01, withCar.link.send.single { it.name == "car type" }.data[1])
        val l2 = CanBoxLink(CanBoxFamilies.HIWORLD, withCar.link, { b, _ -> out += hex(b) })
        l2.start(0)
        l2.tick(1_000)
        assertTrue(out.single().startsWith("5A A5 02 24 "))
    }

    @Test
    fun carTypesOfEachProtocol() {
        assertEquals(listOf(0x0A, 0x09), CanVehicles.HYUNDAI_KIA_HIWORLD.withVariant("Hyundai Accent 2010-2019, all trims").link.send.single { it.name == "car type" }.data)
        assertTrue(CanVehicles.HONDA_HIWORLD.variants.all { it.send.getValue("car type").let { d -> d[1] == 0x02 && d[0] != 0 } })
        assertTrue(CanVehicles.HYUNDAI_KIA_HIWORLD.variants.all { it.send.getValue("car type")[1] == 0x09 })
        // Keys: same layout (0x11, D2 code, D3 = 1 pressed) without LNP002's event bit.
        val k = CanVehicles.HONDA_HIWORLD.keys!!
        assertEquals(null, k.eventByte)
        assertEquals("volume +", k.name(1))
    }
}

class Batch2ProfilesTest {
    private fun frame(
        cmd: Int,
        vararg d: Int,
    ) = CanboxFrame(cmd, ByteArray(d.size) { d[it].toByte() }, true)

    private fun number(
        v: CanVehicle,
        f: CanboxFrame,
        key: String,
    ) = v.decode(f).single { it.key == key }.number!!

    @Test
    fun vwMqbHiworld() {
        val v = CanVehicles.VW_MQB_HIWORLD
        assertEquals(29, v.variants.size)
        // HdMQB7Protocol: no buildCarTypePacket, Hiworld's acknowledgement (shouldSendAck).
        assertTrue(v.link.ack)
        assertTrue(v.link.send.isEmpty())
        assertTrue(!TwrpCanConf.of(v).contains("\nsend "))
        // 0x11: D2 key code, D3 1 while pressed (parseWheelKeyInfo on bytes 6-7 of the frame).
        assertEquals(0x11, v.keys!!.cmd)
        assertEquals("volume +", v.keys!!.name(1))
        assertEquals("mode", v.keys!!.name(11))
        // 0x19: oil D6 - 40, coolant D7 - 40, engine speed D8 * 256 + D9 (HdMQB7Protocol.parseCarInfo, 25).
        val engine = frame(0x19, 0, 0, 0, 0, 0, 0, 130, 125, 0x03, 0x20)
        assertEquals(90.0, number(v, engine, "oil_temp"), 0.01)
        assertEquals(85.0, number(v, engine, "coolant"), 0.01)
        assertEquals(800.0, number(v, engine, "rpm"), 0.01)
        // 0x11 D6-D7: steering, little endian signed, negated (parseWheelInfo).
        assertEquals(-256.0, number(v, frame(0x11, 0x01, 0, 0, 0, 0, 0, 0x00, 0x01, 0, 0), "steering"), 0.01)
    }

    @Test
    fun vwPqHiworld() {
        val v = CanVehicles.VW_PQ_HIWORLD
        assertEquals(42, v.variants.size)
        // HdMQB6Protocol: no buildCarTypePacket, Hiworld's acknowledgement.
        assertTrue(v.link.ack)
        assertTrue(v.link.send.isEmpty())
        // 0x72 D2: key code, pressed while not 0 (parseWheelKeyInfo forces the state to 1).
        assertEquals(0x72, v.keys!!.cmd)
        assertEquals(null, v.keys!!.stateByte)
        assertEquals("voice", v.keys!!.name(15))
        val pressed = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val keys = HiworldWheelKeys(onRelease = { released += it }, spec = v.keys!!) { pressed += it }
        keys.onFrame(frame(0x72, 0x01, 0, 9, 0, 0, 0))
        keys.onFrame(frame(0x72, 0x01, 0, 9, 0, 0, 0))
        keys.onFrame(frame(0x72, 0x01, 0, 0, 0, 0, 0))
        assertEquals(listOf(9), pressed)
        assertEquals(listOf(9), released)
        // 0x13: mileage D0-D2, speed (D3 * 256 + D4) / 10, engine speed D8 * 256 + D9 (parseCarInfo2).
        val engine = frame(0x13, 0x01, 0x00, 0x00, 0x03, 0xE8, 0, 0, 0, 0x03, 0x20)
        assertEquals(65536.0, number(v, engine, "mileage"), 0.01)
        assertEquals(100.0, number(v, engine, "speed"), 0.01)
        assertEquals(800.0, number(v, engine, "rpm"), 0.01)
        // 0x73 D6: outside temperature D6 / 2 - 40 (parseAirInfo).
        assertEquals(20.0, number(v, frame(0x73, 0, 0, 0, 0, 0, 0, 120, 0), "outside"), 0.01)
    }

    @Test
    fun vwMqbRaise() {
        val v = CanVehicles.VW_MQB_RAISE
        assertEquals(61, v.variants.size)
        assertEquals(CanBoxFamilies.RAISE, v.family)
        // ReBaseProtocol: acknowledge = [FF], hello buildConnectPacket = getPacket(0x81, [1]), no car type.
        assertTrue(v.link.ack)
        assertEquals(listOf(0x81), v.link.send.map { it.cmd })
        assertTrue(TwrpCanConf.of(v).contains("\nsend 500 0 2E 81 01 01 7C\n"))
        // 0x20: D0 key code, D1 1 while pressed (parseWheelKeyInfo).
        val pressed = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val keys = HiworldWheelKeys(onRelease = { released += it }, spec = v.keys!!) { pressed += it }
        keys.onFrame(frame(0x20, 3, 1))
        keys.onFrame(frame(0x20, 0, 0))
        assertEquals(listOf(3), pressed)
        assertEquals(listOf(3), released)
        assertEquals("next", v.keys!!.name(3))
        // 0x29: steering D0-D1 little endian signed, negated, in degrees as Hiworld's app (setEps(-byte2Short(bArr, 3), 19918): × 540 / 19918).
        assertEquals(-256 * 540.0 / 19918, number(v, frame(0x29, 0x00, 0x01), "steering"), 0.01)
        // 0x27: outside temperature D1-D2 little endian signed / 10 (parseOutTempInfo).
        assertEquals(21.0, number(v, frame(0x27, 0, 0xD2, 0x00), "outside"), 0.01)
        assertEquals(-10.0, number(v, frame(0x27, 0, 0x9C, 0xFF), "outside"), 0.01)
    }

    @Test
    fun vwPqRaise() {
        val v = CanVehicles.VW_PQ_RAISE
        assertEquals(49, v.variants.size)
        assertEquals(CanBoxFamilies.RAISE, v.family)
        assertTrue(v.link.ack)
        assertEquals(listOf(0x81), v.link.send.map { it.cmd })
        // 0x20: D0 key code, D1 1 while pressed (RePQProtocol.parseWheelKeyInfo).
        assertEquals(0x20, v.keys!!.cmd)
        assertEquals("voice", v.keys!!.name(8))
        // 0x16: speed (D1 << 8 | D0) / 16 (parseVehicleSpeedInfo).
        assertEquals(100.0, number(v, frame(0x16, 0x40, 0x06), "speed"), 0.01)
        // 0x26: steering D0-D1 little endian signed, in degrees (setEps(byte2Short(bArr, 3), 537): × 540 / 537).
        assertEquals(-2 * 540.0 / 537, number(v, frame(0x26, 0xFE, 0xFF), "steering"), 0.01)
    }
}
