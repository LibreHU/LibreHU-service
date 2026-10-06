package org.librehu.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Who may use the service's API, like SuperSU does for root: the LibreHU apps (`org.librehu.*`) and the system are
 * allowed at once, any other app is refused until the user allows it from the notification or the service app.
 *
 * This replaces the `org.librehu.permission.HEADUNIT` check on the service: Android only grants a custom permission
 * to apps installed after the app that defines it, so a LibreHU app installed before the service was refused
 * ("Not allowed to bind") until reinstalled.
 */
class ServiceAccess private constructor(
    context: Context,
) {
    enum class Decision { ALLOWED, DENIED }

    data class Entry(
        val pkg: String,
        val label: String,
        val decision: Decision?,
        val auto: Boolean,
        val lastAt: Long,
    )

    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("access", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())

    /** Apps that used the API (or were decided), most recent first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Throws SecurityException when the calling app may not use the API. */
    fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid < Process.FIRST_APPLICATION_UID) return
        val packages = app.packageManager.getPackagesForUid(uid).orEmpty()
        if (packages.isEmpty()) throw SecurityException("LibreHU-service: unknown caller uid $uid")
        if (packages.any(::isLibreHu)) {
            packages.filter(::isLibreHu).forEach { seen(it, auto = true) }
            return
        }
        val decisions = packages.map { decision(it) }
        if (decisions.any { it == Decision.ALLOWED }) {
            packages.forEach { seen(it, auto = false) }
            return
        }
        packages.forEach { seen(it, auto = false) }
        if (decisions.all { it == null }) ask(packages.first())
        throw SecurityException("LibreHU-service: ${packages.first()} is not allowed (LibreHU-service → Access)")
    }

    fun set(
        pkg: String,
        decision: Decision?,
    ) {
        val o = json()
        val e = o.optJSONObject(pkg) ?: JSONObject()
        if (decision == null) e.remove("d") else e.put("d", decision.name)
        o.put(pkg, e)
        save(o)
        app.getSystemService(NotificationManager::class.java)?.cancel(notificationId(pkg))
    }

    fun forget(pkg: String) {
        val o = json()
        o.remove(pkg)
        save(o)
    }

    private fun decision(pkg: String): Decision? =
        json().optJSONObject(pkg)?.optString("d")?.let { d -> Decision.entries.firstOrNull { it.name == d } }

    private fun seen(
        pkg: String,
        auto: Boolean,
    ) {
        val o = json()
        val e = o.optJSONObject(pkg) ?: JSONObject()
        val now = System.currentTimeMillis()
        // Written at most once a minute: the API is called often.
        if (now - e.optLong("t") < SEEN_PERIOD_MS && e.optBoolean("a") == auto) return
        e.put("t", now).put("a", auto)
        o.put(pkg, e)
        save(o)
    }

    /** Notification "<app> wants to use LibreHU-service" with Allow / Deny. */
    private fun ask(pkg: String) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.access_channel), NotificationManager.IMPORTANCE_HIGH),
        )

        fun action(
            decision: Decision,
            code: Int,
        ) = PendingIntent.getBroadcast(
            app,
            pkg.hashCode() * 2 + code,
            Intent(app, AccessReceiver::class.java).putExtra(EXTRA_PACKAGE, pkg).putExtra(EXTRA_DECISION, decision.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n =
            Notification
                .Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_headunit)
                .setContentTitle(app.getString(R.string.access_request_title, label(pkg)))
                .setContentText(app.getString(R.string.access_request_text, pkg))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(icon(), app.getString(R.string.access_allow), action(Decision.ALLOWED, 0)).build())
                .addAction(Notification.Action.Builder(icon(), app.getString(R.string.access_deny), action(Decision.DENIED, 1)).build())
                .build()
        nm.notify(notificationId(pkg), n)
    }

    private fun icon() =
        android.graphics.drawable.Icon
            .createWithResource(app, R.drawable.ic_stat_headunit)

    private fun notificationId(pkg: String) = NOTIFICATION_BASE + (pkg.hashCode() and 0xFFFF)

    private fun label(pkg: String): String =
        try {
            app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }

    private fun json(): JSONObject =
        try {
            JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

    @Synchronized
    private fun save(o: JSONObject) {
        prefs.edit().putString(KEY, o.toString()).apply()
        _entries.value = load(o)
    }

    private fun load(o: JSONObject = json()): List<Entry> =
        o
            .keys()
            .asSequence()
            .map { pkg ->
                val e = o.getJSONObject(pkg)
                Entry(
                    pkg,
                    label(pkg),
                    e.optString("d").let { d -> Decision.entries.firstOrNull { it.name == d } },
                    e.optBoolean("a"),
                    e.optLong("t"),
                )
            }.sortedByDescending { it.lastAt }
            .toList()

    companion object {
        private const val KEY = "apps"
        private const val CHANNEL = "access"
        private const val NOTIFICATION_BASE = 0x10000
        private const val SEEN_PERIOD_MS = 60_000L
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_DECISION = "decision"

        fun isLibreHu(pkg: String) = pkg.startsWith("org.librehu.")

        @Volatile
        private var instance: ServiceAccess? = null

        fun get(context: Context): ServiceAccess =
            instance ?: synchronized(this) { instance ?: ServiceAccess(context).also { instance = it } }
    }
}

/** Allow / Deny from the access notification (not exported: only our PendingIntents reach it). */
class AccessReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pkg = intent.getStringExtra(ServiceAccess.EXTRA_PACKAGE) ?: return
        val d = ServiceAccess.Decision.entries.firstOrNull { it.name == intent.getStringExtra(ServiceAccess.EXTRA_DECISION) } ?: return
        ServiceAccess.get(context).set(pkg, d)
    }
}
