package org.librehu.service.ril

import android.os.HwBinder
import android.os.HwParcel
import android.os.IHwBinder
import android.os.IHwInterface
import java.util.concurrent.atomic.AtomicInteger

/**
 * Root helper run by app_process (`CLASSPATH=<LibreHU apk> app_process /system/bin
 * org.librehu.service.ril.MtkCallHelper`): lets incoming calls ring on UJC201 boards with a MediaTek modem.
 *
 * MediaTek's RIL (vendor `mtkfusionrild`, `libmtk-ril.so`) does not let the modem ring on its own: on `+EAIC` (incoming
 * call indication) it sends `incomingCallIndication` to the client registered with
 * `vendor.mediatek.hardware.radio@3.0::IRadio.setResponseFunctionsMtk`, and waits for `setCallIndication(serial, mode,
 * callId, seqNo)`; mode 0 lets the call through (`AT+EAIC=0,…`), then the usual `RING` / call state reach Android.
 * MediaTek's own telephony framework is that client on the stock firmware; LineageOS's AOSP telephony only registers
 * `android.hardware.radio` callbacks, so the RIL logs `mRadioIndicationMtk == NULL` and the call never rings.
 *
 * This helper is that client and does nothing else: every other MediaTek indication or response is acknowledged and
 * dropped. Written from the stock vendor binaries (UJC201 vendor 260729, disassembled 23/11/2026), no MediaTek or
 * third-party code:
 * - `vendor.mediatek.hardware.radio@3.0.so`: `BpHwRadio::_hidl_setResponseFunctionsMtk` = transaction 143 (two
 *   binders, response then indication, two-way); `_hidl_setCallIndication` = 196 (four int32, oneway);
 *   `BpHwRadioIndication::_hidl_incomingCallIndication` = 58 (int32 type, then `IncomingCallNotification`: seven
 *   strings, 0x70 bytes).
 * - `librilfusion.so` `radio::incomingCallIndicationInd`: string i of the struct = item i of `+EAIC`.
 * - `libmtk-ril.so` `RtcCallController::handleIncomingCall`: item 0 = call id, 3 = call mode (20 and up: IMS, sent to
 *   the IMS client instead), 4 = sequence number; `autoAcceptIncoming(callId, seqNo)` builds the same request with
 *   mode 0.
 *
 * Instances `slot1`, `slot2` (`ro.telephony.sim.count` 2). stdout: `registered <slot>`, `call <slot> <id> <seq>`,
 * `none` (no MediaTek RIL running), `died <slot>` before exiting when the RIL restarts (the service starts it again).
 */
object MtkCallHelper {
    private const val RADIO = "vendor.mediatek.hardware.radio@3.0::IRadio"
    private const val INDICATION = "vendor.mediatek.hardware.radio@3.0::IRadioIndication"
    private const val RESPONSE = "vendor.mediatek.hardware.radio@3.0::IRadioResponse"
    private const val TX_SET_RESPONSE_FUNCTIONS_MTK = 143
    private const val TX_SET_CALL_INDICATION = 196
    private const val TX_INCOMING_CALL_INDICATION = 58
    private const val CALL_NOTIFICATION_SIZE = 0x70L
    private const val ALLOW = 0
    private const val IMS_CALL_MODE = 20

    // android.hidl.base@1.0::IBase, B_PACK_CHARS(0x0f, …)
    private const val HIDL_INTERFACE_CHAIN = 0x0f43484e
    private const val HIDL_INTERFACE_DESCRIPTOR = 0x0f445343
    private const val HIDL_PING = 0x0f504e47

    private val serial = AtomicInteger(0x4c48_0000)

    /** One callback object; HIDL asks its interface chain when the RIL casts it. */
    private class Callback(
        private val descriptor: String,
        private val onTransaction: (Int, HwParcel) -> Unit,
    ) : HwBinder() {
        private val chain =
            arrayListOf(
                descriptor,
                descriptor.replace("vendor.mediatek.hardware.radio@3.0", "android.hardware.radio@1.2"),
                descriptor.replace("vendor.mediatek.hardware.radio@3.0", "android.hardware.radio@1.1"),
                descriptor.replace("vendor.mediatek.hardware.radio@3.0", "android.hardware.radio@1.0"),
                "android.hidl.base@1.0::IBase",
            )

        override fun queryLocalInterface(name: String): IHwInterface? = null

        override fun onTransact(
            code: Int,
            request: HwParcel,
            reply: HwParcel,
            flags: Int,
        ) {
            when (code) {
                HIDL_INTERFACE_CHAIN -> {
                    reply.writeStatus(HwParcel.STATUS_SUCCESS)
                    reply.writeStringVector(chain)
                    reply.send()
                }
                HIDL_INTERFACE_DESCRIPTOR -> {
                    reply.writeStatus(HwParcel.STATUS_SUCCESS)
                    reply.writeString(descriptor)
                    reply.send()
                }
                HIDL_PING -> {
                    reply.writeStatus(HwParcel.STATUS_SUCCESS)
                    reply.send()
                }
                else -> {
                    try {
                        onTransaction(code, request)
                    } catch (e: Throwable) {
                        System.err.println("transaction $code: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    // Indications and responses of this interface are oneway; a two-way one gets an empty success.
                    if (flags and IHwBinder.FLAG_ONEWAY == 0) {
                        reply.writeStatus(HwParcel.STATUS_SUCCESS)
                        reply.send()
                    }
                }
            }
        }
    }

    private fun allow(
        slot: String,
        radio: IHwBinder,
        request: HwParcel,
    ) {
        request.enforceInterface(INDICATION)
        request.readInt32() // RadioIndicationType
        val blob = request.readBuffer(CALL_NOTIFICATION_SIZE)
        val callId = blob.getString(0x00).trim().toIntOrNull()
        val callMode = blob.getString(0x30).trim().toIntOrNull() ?: 0
        val seqNo = blob.getString(0x40).trim().toIntOrNull()
        if (callId == null || seqNo == null) {
            System.err.println("incoming call on $slot without call id / sequence number")
            return
        }
        if (callMode >= IMS_CALL_MODE) return // IMS call: no IMS stack on LineageOS
        val p = HwParcel()
        p.writeInterfaceToken(RADIO)
        p.writeInt32(serial.incrementAndGet())
        p.writeInt32(ALLOW)
        p.writeInt32(callId)
        p.writeInt32(seqNo)
        radio.transact(TX_SET_CALL_INDICATION, p, HwParcel(), IHwBinder.FLAG_ONEWAY)
        p.releaseTemporaryStorage()
        say("call $slot $callId $seqNo")
    }

    private fun register(slot: String): Boolean {
        val radio =
            try {
                HwBinder.getService(RADIO, slot)
            } catch (_: Exception) {
                null
            } ?: return false
        val indication =
            Callback(INDICATION) { code, request ->
                if (code == TX_INCOMING_CALL_INDICATION) allow(slot, radio, request)
            }
        val response = Callback(RESPONSE) { _, _ -> }
        val p = HwParcel()
        p.writeInterfaceToken(RADIO)
        p.writeStrongBinder(response)
        p.writeStrongBinder(indication)
        val reply = HwParcel()
        radio.transact(TX_SET_RESPONSE_FUNCTIONS_MTK, p, reply, 0)
        reply.verifySuccess()
        p.releaseTemporaryStorage()
        radio.linkToDeath(
            object : IHwBinder.DeathRecipient {
                override fun serviceDied(cookie: Long) {
                    say("died $slot")
                    System.exit(2)
                }
            },
            0,
        )
        // Strong references for the life of the process (the RIL holds proxies only).
        callbacks += listOf(indication, response)
        say("registered $slot")
        return true
    }

    private val callbacks = mutableListOf<Callback>()

    /** One line to the service, at once (stdout of app_process is not flushed line by line). */
    private fun say(line: String) {
        println(line)
        System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        HwBinder.configureRpcThreadpool(2, true)
        val slots = if (args.isNotEmpty()) args.toList() else listOf("slot1", "slot2")
        var any = false
        for (slot in slots) {
            try {
                if (register(slot)) any = true
            } catch (e: Throwable) {
                System.err.println("$slot: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (!any) {
            say("none")
            System.exit(3)
        }
        HwBinder.joinRpcThreadpool()
    }
}
