package org.librehu.service.can

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.librehu.core.can.CanEngine
import org.librehu.core.can.CanVehicleException

/**
 * Packs of car profiles by make, for LibreHU Apps (signature permission `org.librehu.permission.HEADUNIT`), as ordered
 * broadcasts whose result carries the answer:
 * - [ACTION_QUERY]: result data = `{"engine": "2.5.0", "packs": {"renault": 3, …}}` (installed revisions), so that
 *   LibreHU Apps offers only the packs this engine reads and shows which are newer;
 * - [ACTION_INSTALL]: data = the pack file (content URI, read permission granted), extra [EXTRA_SHA256] = its digest
 *   in the index; result code [Activity.RESULT_OK] and the revision installed, or [Activity.RESULT_CANCELED] and why;
 * - [ACTION_REMOVE]: extra [EXTRA_ID]: the make's built-in profiles come back.
 */
class CarPackReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val store = CanVehicleStore.get(context)
        when (intent.action) {
            ACTION_QUERY -> {
                val packs = JSONObject()
                store.packs.value.forEach { (id, p) -> packs.put(id, p.revision) }
                setResult(Activity.RESULT_OK, JSONObject().put("engine", CanEngine.VERSION).put("packs", packs).toString(), null)
            }
            ACTION_INSTALL -> {
                val uri = intent.data
                val sha = intent.getStringExtra(EXTRA_SHA256)
                val pending = goAsync()
                Thread({
                    try {
                        val bytes =
                            uri?.let { u -> context.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                                ?: throw CanVehicleException("no pack file")
                        val p = store.installPack(bytes, sha)
                        pending.setResult(Activity.RESULT_OK, "${p.id} ${p.revision}", null)
                    } catch (e: Exception) {
                        android.util.Log.w("LibreHU-CAN", "car pack not installed: ${e.message}")
                        pending.setResult(Activity.RESULT_CANCELED, e.message ?: e.javaClass.simpleName, null)
                    } finally {
                        pending.finish()
                    }
                }, "car-pack-install").start()
            }
            ACTION_REMOVE -> {
                intent.getStringExtra(EXTRA_ID)?.let { store.removePack(it) }
                setResult(Activity.RESULT_OK, null, null)
            }
        }
    }

    companion object {
        const val ACTION_QUERY = "org.librehu.service.QUERY_CAR_PACKS"
        const val ACTION_INSTALL = "org.librehu.service.INSTALL_CAR_PACK"
        const val ACTION_REMOVE = "org.librehu.service.REMOVE_CAR_PACK"
        const val EXTRA_SHA256 = "sha256"
        const val EXTRA_ID = "id"
    }
}
