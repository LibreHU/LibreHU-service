package org.librehu.service.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The alarm of the suspend test ([Standby.testSuspend]); not exported, only this app's PendingIntent reaches it. */
class StandbyTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Standby.get(context).finishTest()
    }
}
