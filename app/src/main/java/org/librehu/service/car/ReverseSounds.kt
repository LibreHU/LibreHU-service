package org.librehu.service.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Sounds of the rear view: the one played when the reverse gear is engaged, parking beeps getting faster as an
 * obstacle gets closer (level 0 = silent .. 4 = continuous), the movement alert, and the music while reversing (audio
 * focus: lowered or paused). Main thread.
 */
class ReverseSounds(
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tones: ToneGenerator? = null
    private var tonesVolume = -1
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var level = 0
    private var continuous = false
    private var lastAlert = 0L
    private var volume = 80

    private val beep =
        object : Runnable {
            override fun run() {
                val l = level
                if (l <= 0) return
                if (l >= 4) {
                    if (!continuous) {
                        continuous = tone(ToneGenerator.TONE_DTMF_D, -1)
                    }
                    return
                }
                stopContinuous()
                tone(ToneGenerator.TONE_DTMF_D, BEEP_MS)
                main.postDelayed(this, PERIODS[l])
            }
        }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
    }

    /** Sound of the reverse gear. */
    fun engage(s: ReverseViewSettings) {
        setVolume(s.soundVolume)
        when (s.sound) {
            EngageSound.NONE -> {}
            EngageSound.BEEP -> tone(ToneGenerator.TONE_PROP_BEEP, 250)
            EngageSound.DOUBLE -> tone(ToneGenerator.TONE_PROP_BEEP2, 400)
            EngageSound.CHIME -> tone(ToneGenerator.TONE_PROP_ACK, 500)
            EngageSound.CUSTOM -> if (s.soundUri.isEmpty()) tone(ToneGenerator.TONE_PROP_BEEP, 250) else play(Uri.parse(s.soundUri))
        }
    }

    /** Parking beeps: 0 = none, 1 slow .. 3 fast, 4 = continuous. */
    fun setLevel(l: Int) {
        val n = l.coerceIn(0, 4)
        if (n == level) return
        val wasIdle = level == 0
        level = n
        main.removeCallbacks(beep)
        if (n < 4) stopContinuous()
        if (n > 0) {
            // A closer obstacle beeps at once; a farther one waits for its slower period.
            if (wasIdle || n == 4) main.post(beep) else main.postDelayed(beep, PERIODS[n.coerceAtMost(3)])
        }
    }

    /** Something moves behind the car: one alert, at most every [ALERT_GAP_MS]. */
    fun alert() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAlert < ALERT_GAP_MS || level >= 4) return
        lastAlert = now
        tone(ToneGenerator.TONE_PROP_NACK, 300)
    }

    fun duckMedia(mode: MediaDuring) {
        if (mode == MediaDuring.KEEP || focus != null) return
        val request =
            AudioFocusRequest
                .Builder(
                    if (mode ==
                        MediaDuring.PAUSE
                    ) {
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    } else {
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    },
                ).setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener { }
                .build()
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
    }

    fun stop() {
        setLevel(0)
        stopContinuous()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        player?.let { runCatching { it.release() } }
        player = null
        tones?.release()
        tones = null
    }

    private fun stopContinuous() {
        if (continuous) tones?.stopTone()
        continuous = false
    }

    private fun tone(
        type: Int,
        ms: Int,
    ): Boolean {
        if (volume == 0) return false
        val t =
            tones.takeIf { tonesVolume == volume }
                ?: try {
                    tones?.release()
                    ToneGenerator(AudioManager.STREAM_MUSIC, volume).also {
                        tones = it
                        tonesVolume = volume
                    }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "tones: ${e.message}")
                    return false
                }
        return t.startTone(type, ms)
    }

    private fun play(uri: Uri) {
        player?.let { runCatching { it.release() } }
        player =
            try {
                MediaPlayer().apply {
                    setAudioAttributes(ATTRS)
                    setDataSource(context, uri)
                    setVolume(volume / 100f, volume / 100f)
                    setOnCompletionListener {
                        it.release()
                        if (player === it) player = null
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sound $uri: ${e.message}")
                tone(ToneGenerator.TONE_PROP_BEEP, 250)
                null
            }
    }

    companion object {
        private const val TAG = "LibreHU-Reverse"
        private const val BEEP_MS = 110
        private const val ALERT_GAP_MS = 1500L

        /** Time between beeps for levels 1..3 (index = level). */
        private val PERIODS = longArrayOf(0, 850, 480, 250)

        private val ATTRS =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
    }
}
