package org.librehu.service.car

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import android.view.Surface

/**
 * Live rear camera stream for LibreHU Launcher (bind with action [ACTION], package org.librehu.service): the launcher
 * shows its own view in place of the rear view widget and hands its surface over; the camera draws straight into it
 * ([ReverseWidgetLive.attachSurface]), at full rate, which an app widget cannot do. Apps signed with the LibreHU key
 * only.
 *
 * Messages (Messenger): [MSG_QUERY] (replyTo) → [MSG_INFO]: `enabled`, `width` / `height` (buffer size to give the
 * SurfaceTexture), `mirror`, `flip`, `rotation`, `zoom`, `offsetX`, `offsetY`; [MSG_ATTACH] (`surface`, replyTo) →
 * [MSG_STATE] as it goes (arg1 = ReverseWidgetLive.STATE_*, `text`); [MSG_DETACH]; [MSG_TOGGLE] opens or closes the
 * full rear view.
 */
class RearPreviewService : Service() {
    private val messenger by lazy { Messenger(IncomingHandler()) }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (packageManager.checkSignatures(Process.myUid(), msg.sendingUid) != PackageManager.SIGNATURE_MATCH) {
                Log.w(TAG, "refused uid ${msg.sendingUid}")
                return
            }
            val live = ReverseWidgetLive.get(this@RearPreviewService)
            when (msg.what) {
                MSG_QUERY -> {
                    reply(msg.replyTo)
                }

                MSG_ATTACH -> {
                    val surface =
                        msg.data?.let {
                            it.classLoader = Surface::class.java.classLoader
                            @Suppress("DEPRECATION")
                            it.getParcelable<Surface>("surface")
                        }
                    if (surface != null && surface.isValid) live.attachSurface(surface, msg.replyTo) else live.detachSurface()
                }

                MSG_DETACH -> {
                    live.detachSurface()
                }

                MSG_TOGGLE -> {
                    ReverseView.get(this@RearPreviewService).toggle()
                }
            }
        }

        /** Buffer size and image settings, read on a thread (camera characteristics). */
        private fun reply(to: Messenger?) {
            to ?: return
            Thread({
                val view = ReverseView.get(this@RearPreviewService)
                val s = view.settings.value
                val size = view.previewSize(s)
                val data =
                    Bundle().apply {
                        putBoolean("enabled", s.widgetLive != WidgetLive.OFF && size != null)
                        putInt("width", size?.width ?: 0)
                        putInt("height", size?.height ?: 0)
                        putBoolean("mirror", s.mirror)
                        putBoolean("flip", s.flip)
                        putInt("rotation", s.rotation)
                        putInt("zoom", s.zoom)
                        putInt("offsetX", s.offsetX)
                        putInt("offsetY", s.offsetY)
                    }
                runCatching { to.send(Message.obtain(null, MSG_INFO).apply { this.data = data }) }
            }, "rear-preview-info").start()
        }
    }

    companion object {
        private const val TAG = "LibreHU-RearPreview"
        const val ACTION = "org.librehu.service.REAR_PREVIEW"
        const val MSG_QUERY = 1
        const val MSG_ATTACH = 2
        const val MSG_DETACH = 3
        const val MSG_TOGGLE = 4
        const val MSG_INFO = 10
        const val MSG_STATE = 11
    }
}
