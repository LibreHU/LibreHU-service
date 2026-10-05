package org.librehu.service.bt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.util.Log
import org.librehu.core.bt.PhonePhase

/**
 * What the head unit does around a call, like Jancar's btservice (`RingUtil`) and ivi-services' call priorities:
 * plays the ringtone itself (in-band ringing is off on this unit, `persist.bluetooth.atc.inbandringtone`), and holds
 * the audio focus while ringing or talking so that the radio and the music players pause.
 */
internal class BtRinger(
    private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var focus: AudioFocusRequest? = null
    private var phase = PhonePhase.IDLE

    fun update(
        phase: PhonePhase,
        ring: Boolean,
    ) {
        if (ring) startRinging() else stopRinging()
        if (phase == this.phase) return
        this.phase = phase
        when (phase) {
            PhonePhase.IDLE -> abandonFocus()
            PhonePhase.RINGING -> requestFocus(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            PhonePhase.OUTGOING, PhonePhase.IN_CALL -> requestFocus(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        }
    }

    fun release() {
        stopRinging()
        abandonFocus()
        phase = PhonePhase.IDLE
    }

    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        val uri =
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone =
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                isLooping = true
                try {
                    play()
                } catch (e: Exception) {
                    Log.w(TAG, "Ringtone: ${e.message}")
                }
            }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }

    private fun requestFocus(usage: Int) {
        abandonFocus()
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
                .setOnAudioFocusChangeListener { }
                .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val TAG = "LibreHU-BT"
    }
}
