package org.librehu.service.atc

import android.content.Context
import org.librehu.service.root.RootShell

/**
 * Hands the display over from the ARM2 to Android, as Jancar's hd2cam does at start-up: the ARM2 fast reverse runs
 * before Android and keeps layers 1 and 2 of the OVL0 display mixer until an app calls `BackCar.sendReadyToArm2()`
 * (kernel `IOCTL_FSC_NOTIFY_APP_READY`, `dispsys/ddp_ovl.c`); without hd2cam nothing does, and every hardware-decoded
 * video stays solid green (Android Auto, video players). Report and tests by Reo Au In (docs/atc.md).
 *
 * Run at the service's start and after a real standby, through the root helper ([AtcHelper] `arm2ready`): nothing is
 * sent when the ARM2 has already handed over (status 2, e.g. hd2cam still installed). Not while reverse is engaged:
 * the ARM2 shows the rear camera then, Android's cameras only come once it has exited.
 */
object Arm2Handover {
    private const val HANDED_OVER = "2"
    private const val TRIES = 3
    private const val RETRY_MS = 10_000L
    private const val REVERSE_WAIT_MS = 5 * 60_000L

    @Volatile
    private var running = false

    fun check(
        context: Context,
        reverse: () -> Boolean,
        log: (String) -> Unit,
    ) {
        if (running) return
        running = true
        Thread({
            try {
                run(context.applicationContext, reverse, log)
            } finally {
                running = false
            }
        }, "arm2-handover").start()
    }

    private fun run(
        context: Context,
        reverse: () -> Boolean,
        log: (String) -> Unit,
    ) {
        if (!RootShell.isAvailable()) {
            log("ARM2: no root, the handover to Android is left to Jancar's hd2cam")
            return
        }
        for (attempt in 1..TRIES) {
            var waited = 0L
            while (reverse() && waited < REVERSE_WAIT_MS) {
                Thread.sleep(1000)
                waited += 1000
            }
            val r = AtcTools.arm2Ready(context)
            if (r == null) {
                log("ARM2: root helper failed")
                return
            }
            r["error"]?.let {
                log("ARM2: $it")
                return
            }
            val before = r["before"]
            val after = r["after"]
            when {
                before == HANDED_OVER -> {
                    log("ARM2: already handed over to Android (status 2)")
                    return
                }

                after == HANDED_OVER -> {
                    log("ARM2: handed over to Android (status $before -> 2): hardware video and cameras free")
                    return
                }

                else -> {
                    log("ARM2: status $before -> $after after sendReadyToArm2 = ${r["ready"]} (try $attempt of $TRIES)")
                }
            }
            if (attempt < TRIES) Thread.sleep(RETRY_MS)
        }
    }
}
