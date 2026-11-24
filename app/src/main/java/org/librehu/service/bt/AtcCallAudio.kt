package org.librehu.service.bt

import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Call audio of the car's hands-free link (SCO) through the Autochips audio HAL.
 *
 * The stock firmware's Bluetooth app (MediaTek / Autochips `MtkBluetooth`) tells the HAL about the call audio with its
 * own keys: `bt_wbs=on|off`, `atc_hfp_set_sampling_rate=8000|16000`, `atc_hfp_enable=true|false` and
 * `atc_hfp_volume=0..15`. The HAL of the UJC201 knows them (`strings audio.primary.ac8257.so`, 24/11/2026), next to the
 * AOSP ones. LineageOS's Bluetooth app (AOSP) only sends `hfp_set_sampling_rate`, `hfp_enable` and `hfp_volume`: in the
 * logs of LineageOS 17.1 calls (23 and 24/11/2026) the HAL then never opened the Bluetooth voice path (BTCVSD) nor its
 * echo canceller, so the car had no call audio and no microphone. Sent here on every change of the SCO link, like the
 * stock app; `adb shell setprop persist.librehu.atc_hfp 0` turns it off.
 */
internal class AtcCallAudio(
    private val context: Context,
) {
    /** The Autochips call path is on (we turned it on and have not turned it off yet). */
    private var on = false

    private val audio: AudioManager?
        get() = context.getSystemService(AudioManager::class.java)

    /** `…headsetclient.profile.action.AUDIO_STATE_CHANGED`. */
    fun onScoChanged(intent: Intent) {
        val am = audio ?: return
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            AUDIO_CONNECTED -> {
                if (!enabled()) return
                val wbs = intent.getBooleanExtra(EXTRA_AUDIO_WBS, false)
                set(am, "bt_wbs=" + if (wbs) "on" else "off")
                set(am, "atc_hfp_set_sampling_rate=" + if (wbs) 16000 else 8000)
                set(am, "atc_hfp_enable=true")
                set(am, "atc_hfp_volume=${hfVolume(am)}")
                if (!on) {
                    runCatching {
                        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
                        if (Build.VERSION.SDK_INT >= 33) {
                            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            context.registerReceiver(volumeReceiver, filter)
                        }
                    }
                }
                on = true
                Log.i(TAG, "Autochips call audio on (${if (wbs) "16 kHz, mSBC" else "8 kHz, CVSD"})")
            }
            AUDIO_DISCONNECTED -> off()
        }
    }

    /** `android.media.VOLUME_CHANGED_ACTION`, only while the path is on: the call volume follows the voice call stream. */
    private val volumeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                if (!on || intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_VOICE_CALL) return
                audio?.let { set(it, "atc_hfp_volume=${hfVolume(it)}") }
            }
        }

    /** SCO gone, Bluetooth off or the service stopping: the path is closed if we opened it. */
    fun off() {
        if (!on) return
        on = false
        runCatching { context.unregisterReceiver(volumeReceiver) }
        audio?.let { set(it, "atc_hfp_enable=false") }
        Log.i(TAG, "Autochips call audio off")
    }

    /** Voice call volume on the 0..15 scale of the hands-free profile (AOSP `amToHfVol`). */
    private fun hfVolume(am: AudioManager): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val vol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        return Math.round(vol * HF_MAX_VOLUME / max.toFloat()).coerceIn(0, HF_MAX_VOLUME)
    }

    private fun set(
        am: AudioManager,
        keyValue: String,
    ) {
        runCatching { am.setParameters(keyValue) }.onFailure { Log.w(TAG, "$keyValue: ${it.message}") }
    }

    private fun enabled(): Boolean =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, PROP_ENABLED, "1") as String
        }.getOrDefault("1") != "0"

    private companion object {
        const val TAG = "LibreHU-BT"
        const val PROP_ENABLED = "persist.librehu.atc_hfp"
        const val EXTRA_AUDIO_WBS = "android.bluetooth.headsetclient.extra.AUDIO_WBS"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val AUDIO_CONNECTED = 2
        const val AUDIO_DISCONNECTED = 0
        const val HF_MAX_VOLUME = 15
    }
}
