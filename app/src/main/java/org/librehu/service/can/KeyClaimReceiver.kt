package org.librehu.service.can

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Buttons handled by another app (LibreHU BtnRemap): it sends the ids of its mapped buttons ("can:<code>"), and the
 * service no longer runs its own action for them (one action per press). Protected by the HEADUNIT permission.
 */
class KeyClaimReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        CanWheelKeys.get(context).setClaims(pkg, keys.toSet())
    }

    companion object {
        const val ACTION = "org.librehu.service.CLAIM_KEYS"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_KEYS = "keys"
    }
}
