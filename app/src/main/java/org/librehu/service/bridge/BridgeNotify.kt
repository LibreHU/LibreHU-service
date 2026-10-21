package org.librehu.service.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.librehu.service.R

/** "<phone> wants to pair with the LibreHU app": Allow / Refuse, for a Bluetooth pairing without the QR code. */
object BridgeNotify {
    private const val CHANNEL = "bridge"
    const val EXTRA_ID = "id"
    const val EXTRA_ALLOW = "allow"

    fun ask(
        context: Context,
        id: String,
        name: String,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.bridge_channel), NotificationManager.IMPORTANCE_HIGH))
        fun action(
            allow: Boolean,
            label: Int,
        ): Notification.Action {
            val pi =
                PendingIntent.getBroadcast(
                    context,
                    id.hashCode() * 2 + if (allow) 1 else 0,
                    Intent(context, BridgeAnswerReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_ALLOW, allow),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return Notification.Action
                .Builder(
                    android.graphics.drawable.Icon
                        .createWithResource(context, R.drawable.ic_stat_headunit),
                    context.getString(label),
                    pi,
                ).build()
        }
        nm.notify(
            id.hashCode(),
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_headunit)
                .setContentTitle(context.getString(R.string.bridge_request_title, name))
                .setContentText(context.getString(R.string.bridge_request_text))
                .setAutoCancel(true)
                .addAction(action(true, R.string.access_allow))
                .addAction(action(false, R.string.access_deny))
                .build(),
        )
    }
}

class BridgeAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getStringExtra(BridgeNotify.EXTRA_ID) ?: return
        BridgeServer.get(context).answer(id, intent.getBooleanExtra(BridgeNotify.EXTRA_ALLOW, false))
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(id.hashCode())
    }
}
