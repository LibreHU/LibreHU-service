package org.librehu.service.car

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Blinks the front panel LEDs ([FrontLed]) when a new notification arrives. Needs the notification access
 * (Android settings, or `cmd notification allow_listener` as root from the Lights tab).
 */
class NotificationBlinkService : NotificationListenerService() {
    /** Keys already seen: an update marked "alert only once" (progress, counters) does not blink again. */
    private val seen = LinkedHashSet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val led = FrontLed.get(this)
        if (!led.settings.value.blinkOnNotification) return
        val n = sbn.notification
        val first = seen.add(sbn.key)
        if (seen.size > MAX_KEYS) seen.remove(seen.first())
        val silent =
            sbn.isOngoing ||
                n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 ||
                n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
                (!first && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) ||
                sbn.packageName == packageName ||
                sbn.packageName == "android"
        if (!silent) led.blink()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
    }

    private companion object {
        const val MAX_KEYS = 200
    }
}
