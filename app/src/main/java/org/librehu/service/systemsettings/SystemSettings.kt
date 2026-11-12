package org.librehu.service.systemsettings

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.display.DarkMode
import org.librehu.service.display.DisplayController
import org.librehu.service.projection.AndroidAuto
import org.librehu.service.projection.HeadunitApp

/**
 * LibreHU's entries in Android's Settings, under LineageOS (the service is a system app there: /system/priv-app).
 *
 * Android 9's Settings adds to its pages the activities of system apps that answer
 * `com.android.settings.action.EXTRA_SETTINGS`, in the page named by their `com.android.settings.category` (SettingsLib
 * TileUtils, Settings DashboardFragment). The manifest declares them as aliases of MainActivity (`.systemsettings.*`);
 * each names in [META_TAB] the page of the service it opens. Settings ignores the entries of a user app (stock ROM),
 * and they go with the service: uninstalled or disabled, they disappear.
 *
 * The summaries of the entries inside Settings' pages are live: Settings calls [SummaryProvider.call] with the first
 * path segment of their `com.android.settings.summary_uri` as the method, each time the page shows, and displays
 * [KEY_SUMMARY] of the bundle. The entry of Settings' home page has a fixed summary (resource): Android 9 reads live ones
 * there for its own entries only (DashboardAdapter, SummaryLoader).
 */
object SystemSettings {
    /** Meta-data of an entry: the [org.librehu.service.ui.Tab] it opens. */
    const val META_TAB = "org.librehu.service.TAB"

    /** Key of the summary in the bundle Settings reads (TileUtils.META_DATA_PREFERENCE_SUMMARY). */
    const val KEY_SUMMARY = "com.android.settings.summary"

    /** Methods of [SummaryProvider], the first path segment of the summary URIs of the manifest. */
    const val DISPLAY = "display"
    const val BLUETOOTH = "bluetooth"
    const val ANDROID_AUTO = "android_auto"
    const val SYSTEM = "system"

    private const val CONNECTED = 2

    /** The page an entry opens: [META_TAB] of the alias the activity was started through, null for another start. */
    fun tabOf(
        context: Context,
        component: ComponentName?,
    ): String? {
        if (component == null || component.packageName != context.packageName) return null
        return try {
            context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA).metaData?.getString(META_TAB)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Live summary of an entry, by the method Settings calls; null for an unknown one. */
    fun summary(
        context: Context,
        method: String,
    ): String? =
        when (method) {
            DISPLAY -> display(context)
            BLUETOOTH -> bluetooth(context)
            ANDROID_AUTO -> androidAuto(context)
            SYSTEM -> system(context)
            else -> null
        }

    private fun display(context: Context): String {
        val s = DisplayController.get(context).settings.value
        val dark = s.darkMode == DarkMode.HEADLIGHTS
        return context.getString(
            when {
                s.dimWithHeadlights && dark -> R.string.sys_settings_display_dim_dark
                s.dimWithHeadlights -> R.string.sys_settings_display_dim
                dark -> R.string.sys_settings_display_dark
                else -> R.string.sys_settings_display_other
            },
        )
    }

    private fun bluetooth(context: Context): String {
        // Same process: a direct call to the running service's Bluetooth module; null while it is not started.
        val status = LibreHuService.btApi?.status ?: return context.getString(R.string.sys_settings_bluetooth_other)
        return when {
            !status.enabled -> context.getString(R.string.sys_settings_bluetooth_off)
            status.deviceName.isNotEmpty() && (status.hfpState == CONNECTED || status.a2dpState == CONNECTED) ->
                context.getString(R.string.sys_settings_bluetooth_connected, status.deviceName)
            else -> context.getString(R.string.bt_no_phone)
        }
    }

    private fun androidAuto(context: Context): String {
        val app = AndroidAuto.installed(context) ?: return context.getString(R.string.aa_app_none)
        val state =
            when (AndroidAuto.session.value.state) {
                "projecting" -> R.string.aa_state_projecting
                "connected" -> R.string.aa_state_connected
                "connecting" -> R.string.aa_state_connecting
                else -> if (app == HeadunitApp.LIBREHU) R.string.aa_app_librehu else R.string.aa_app_upstream
            }
        return context.getString(state)
    }

    private fun system(context: Context): String =
        context.getString(
            when (ServiceState.link.value.state) {
                LibreHuService.Link.RUNNING -> R.string.link_running
                LibreHuService.Link.NO_MCU -> R.string.link_no_mcu
                LibreHuService.Link.BLOCKED_BY_IVI -> R.string.link_blocked
                LibreHuService.Link.STOPPED -> R.string.sys_settings_link_stopped
            },
        )
}

/**
 * Live summaries of LibreHU's entries for Android's Settings ([SystemSettings]): call() only. A summary names the
 * connected phone: answered to the system uid only (Settings runs as `android.uid.system`), root (adb tests) and the
 * app itself; anyone else gets null, never an exception (Settings reads them on a thread of its own without catching
 * one).
 */
class SummaryProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val app = Binder.getCallingUid() % PER_USER_RANGE
        if (app != Process.SYSTEM_UID && app != ROOT_UID && app != Process.myUid() % PER_USER_RANGE) return null
        val c = context ?: return null
        val text =
            try {
                SystemSettings.summary(c, method)
            } catch (e: Exception) {
                Log.w(TAG, "summary $method: $e")
                null
            } ?: return null
        return Bundle().apply { putString(SystemSettings.KEY_SUMMARY, text) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    private companion object {
        const val TAG = "LibreHU-SysSettings"
        const val PER_USER_RANGE = 100_000
        const val ROOT_UID = 0
    }
}
