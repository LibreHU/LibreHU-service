package org.librehu.service.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Pairing of the phone app from another LibreHU app (LibreHU Launcher's first start assistant):
 * `content://org.librehu.service.bridgepair`, `call()` only, for apps holding the signature permission
 * `org.librehu.permission.BRIDGE` (checked here: Android does not check a provider's permissions for `call()`).
 *
 * - `state` → `enabled`, `connected` (names), `devices` (names of the paired phones), `requests` (ids) and
 *   `request_names` (Bluetooth pairings waiting for an answer), `until` (end of the QR code's validity, 0 without);
 * - `pair` → `uri` (QR code text, one-time token valid 5 min), `fingerprint`, `until`; turns the server on;
 * - `cancel` (QR code closed), `answer` (arg = request id, extra `allow`), `enable` (extra `on`).
 */
class BridgePairProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        val ctx = context ?: return null
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) throw SecurityException("needs $PERMISSION")
        val server = BridgeServer.get(ctx)
        when (method) {
            "pair" -> {
                if (!server.enabled.value) server.setEnabled(true)
                val uri = server.pairingUri()
                return Bundle().apply {
                    putString("uri", uri)
                    putString("fingerprint", server.fingerprint)
                    putLong("until", server.pairingUntil)
                }
            }
            "cancel" -> server.cancelPairing()
            "answer" -> if (arg != null) server.answer(arg, extras?.getBoolean("allow") == true)
            "enable" -> server.setEnabled(extras?.getBoolean("on") == true)
            "state" -> {}
            else -> return null
        }
        return Bundle().apply {
            putBoolean("enabled", server.enabled.value)
            putStringArray("connected", server.connected.value.toTypedArray())
            putStringArray("devices", server.devices.value.map { it.name }.toTypedArray())
            putStringArray("requests", server.requests.value.map { it.id }.toTypedArray())
            putStringArray("request_names", server.requests.value.map { it.name }.toTypedArray())
            putLong("until", server.pairingUntil)
        }
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

    companion object {
        const val PERMISSION = "org.librehu.permission.BRIDGE"
    }
}
