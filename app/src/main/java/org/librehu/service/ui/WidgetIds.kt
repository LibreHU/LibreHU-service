package org.librehu.service.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Ids of the [provider]'s widgets on the home screen, or none while the user is still locked. Before the first unlock
 * after a boot, [AppWidgetManager.getAppWidgetIds] throws "User 0 must be unlocked for widgets to be available": the
 * service, started in direct boot, crashed once at each boot under LineageOS (log of 13/11/2026). Nothing is lost:
 * at unlock Android sends every provider that has widgets an update (AppWidgetServiceImpl.onUserUnlocked).
 */
fun AppWidgetManager.idsWhenUnlocked(
    context: Context,
    provider: Class<*>,
): IntArray {
    if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return IntArray(0)
    return try {
        getAppWidgetIds(ComponentName(context, provider)) ?: IntArray(0)
    } catch (_: IllegalStateException) {
        IntArray(0)
    }
}
