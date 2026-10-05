package org.librehu.service.bt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.util.Log

/**
 * Bluetooth music: the Bluetooth app publishes the phone's player (AVRCP controller) as a MediaBrowserService, like
 * Jancar's btservice uses it (`A2dpUtil`). Its name changed between builds (AOSP 9:
 * `a2dpsink.mbs.A2dpMediaBrowserService`, this ROM: `avrcpcontroller.BluetoothMediaBrowserService`), so it is looked up.
 */
internal class BtMedia(
    private val context: Context,
    private val main: Handler,
    private val onChanged: (BtMediaInfo) -> Unit,
) {
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null

    @Volatile
    var info = BtMediaInfo()
        private set

    private var a2dpConnected = false

    private val controllerCallback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

            override fun onSessionDestroyed() {
                controller = null
                publish()
            }
        }

    private val connectionCallback =
        object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                controller?.unregisterCallback(controllerCallback)
                controller = MediaController(context, b.sessionToken).also { it.registerCallback(controllerCallback, main) }
                if (a2dpConnected) takeFocusIfIdle()
                publish()
            }

            override fun onConnectionSuspended() {
                controller?.unregisterCallback(controllerCallback)
                controller = null
                publish()
            }

            override fun onConnectionFailed() {
                Log.w(TAG, "Bluetooth media browser refused the connection")
                browser = null
            }
        }

    /** A2DP sink connection changed: (re)attach to the Bluetooth player. */
    fun setA2dpConnected(connected: Boolean) {
        val was = a2dpConnected
        a2dpConnected = connected
        if (connected && controller == null) connectBrowser()
        if (connected && !was) takeFocusIfIdle()
        publish()
    }

    /**
     * Android 9's A2DP sink pauses the phone when it starts playing while the car does not hold the audio focus
     * (`A2dpSinkStreamHandler`, SRC_STR_START / SRC_PLAY): "play on the phone pauses at once". The Bluetooth media
     * session takes the focus on prepare(): done when the phone connects, unless another app is playing (FM…).
     */
    private fun takeFocusIfIdle() {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.isMusicActive == true) return
        controller?.transportControls?.prepare()
    }

    fun start() = connectBrowser()

    fun stop() {
        controller?.unregisterCallback(controllerCallback)
        controller = null
        browser?.disconnect()
        browser = null
    }

    /** prepare() first: the focus, without which the sink pauses the phone again (see [takeFocusIfIdle]). */
    fun play() {
        val c = controls() ?: return
        c.prepare()
        c.play()
    }

    fun pause() = controls()?.pause()

    fun playPause() {
        if (info.playing) pause() else play()
    }

    fun next() = controls()?.skipToNext()

    fun previous() = controls()?.skipToPrevious()

    fun stopPlayback() = controls()?.stop()

    private fun controls(): MediaController.TransportControls? {
        if (controller == null) connectBrowser()
        return controller?.transportControls
    }

    private fun connectBrowser() {
        if (browser != null) return
        val component = findService() ?: return
        browser =
            MediaBrowser(context, component, connectionCallback, null).also {
                try {
                    it.connect()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Media browser: ${e.message}")
                }
            }
    }

    private fun findService(): ComponentName? {
        val found =
            context.packageManager
                .queryIntentServices(Intent(MEDIA_BROWSER_SERVICE_ACTION).setPackage(BLUETOOTH_PACKAGE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        return found.firstOrNull { it.className in KNOWN } ?: found.firstOrNull()
    }

    private fun publish() {
        val c = controller
        val state = c?.playbackState
        val meta = c?.metadata
        val next =
            BtMediaInfo(
                connected = a2dpConnected,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                positionMs = state?.position ?: 0,
            )
        if (next != info) {
            info = next
            onChanged(next)
        }
    }

    private companion object {
        const val TAG = "LibreHU-BT"
        const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        const val MEDIA_BROWSER_SERVICE_ACTION = "android.media.browse.MediaBrowserService"
        val KNOWN =
            setOf(
                "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService",
                "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService",
            )
    }
}
