package org.librehu.service.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.librehu.core.audio.Bd37534
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Audio diagnostics: level of Android's output mix (Visualizer on the global session, peak and RMS), estimated level
 * of each speaker (the BD37534 is write-only over I2C: output level + volume curve + fader of that speaker), Android
 * streams with their volume, active players, registers last written to the chip, and a test tone per speaker.
 */
@Composable
fun AudioDiagScreen(
    client: ServiceClient,
    actions: AppActions,
) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(AudioManager::class.java) }
    val a = client.audio
    val link by ServiceState.link.collectAsStateWithLifecycle()
    var peakDb by remember { mutableFloatStateOf(SILENCE_DB) }
    var rmsDb by remember { mutableFloatStateOf(SILENCE_DB) }
    var meterError by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val v =
            try {
                Visualizer(0).apply {
                    measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                    enabled = true
                }
            } catch (e: Throwable) {
                meterError = e.message ?: e.javaClass.simpleName
                null
            }
        meter = v
        onDispose {
            meter = null
            runCatching { v?.release() }
        }
    }
    LaunchedEffect(Unit) {
        val m = Visualizer.MeasurementPeakRms()
        while (true) {
            meter?.let { v ->
                if (runCatching { v.getMeasurementPeakRms(m) }.getOrDefault(Visualizer.ERROR) == Visualizer.SUCCESS) {
                    // mB, -9600 = silence; a little decay for the eye.
                    peakDb = maxOf(m.mPeak / 100f, peakDb - DECAY_DB)
                    rmsDb = maxOf(m.mRms / 100f, rmsDb - DECAY_DB)
                }
            }
            tick++
            delay(METER_PERIOD_MS)
        }
    }

    Page(stringResource(R.string.tab_audio_diag)) {
        Card(stringResource(R.string.adiag_levels)) {
            if (meterError.isNotEmpty()) {
                Hint(stringResource(R.string.adiag_meter_error, meterError))
                Actions { Pill(stringResource(R.string.adiag_permission), onClick = actions.requestAudioPermission) }
            }
            Meter(stringResource(R.string.adiag_android_peak), peakDb)
            Meter(stringResource(R.string.adiag_android_rms), rmsDb)
            Gap()
            Hint(stringResource(R.string.adiag_speakers_hint))
            val levels = Bd37534.speakerLevels(a.balance, a.fade)
            val volDb = Bd37534.VOLUME_CURVE[a.volume.coerceIn(0, Bd37534.MAX_VOLUME)]
            val gain = if (a.muted || link.state != LibreHuService.Link.RUNNING) null else volDb
            val names = listOf(R.string.adiag_fl, R.string.adiag_fr, R.string.adiag_rl, R.string.adiag_rr)
            for (i in 0 until 4) {
                Meter(stringResource(names[i]), gain?.let { rmsDb + it + levels[i] } ?: SILENCE_DB, "${levels[i]} dB")
            }
            Meter(
                stringResource(R.string.adiag_sub),
                if (a.subwoofer && gain != null) rmsDb + gain + Bd37534.SUB_BASE_DB + a.subLevel else SILENCE_DB,
                if (a.subwoofer) "${Bd37534.SUB_BASE_DB + a.subLevel} dB" else stringResource(R.string.adiag_off),
            )
            Hint(
                stringResource(
                    R.string.adiag_chain,
                    if (a.source == 1) "AUX" else "Android",
                    a.volume,
                    volDb,
                    if (a.muted) stringResource(R.string.mute) else "—",
                ),
            )
        }

        Card(stringResource(R.string.adiag_test)) {
            Hint(stringResource(R.string.adiag_test_hint))
            var testing by remember { mutableStateOf(false) }
            Actions {
                for ((label, target) in SPEAKER_TESTS) {
                    Pill(stringResource(label), enabled = !testing && link.state == LibreHuService.Link.RUNNING) {
                        testing = true
                        Thread({
                            speakerTest(client, a, target)
                            testing = false
                        }, "speaker-test").start()
                    }
                }
            }
        }

        Card(stringResource(R.string.adiag_streams)) {
            Hint(stringResource(R.string.adiag_streams_hint))
            val volumes = remember(tick / 5) { STREAMS.map { (stream, _) -> am.getStreamVolume(stream) to am.getStreamMaxVolume(stream) } }
            val musicActive = remember(tick / 5) { am.isMusicActive }
            for ((i, entry) in STREAMS.withIndex()) {
                val (stream, label) = entry
                val (cur, max) = volumes[i]
                val active = stream == AudioManager.STREAM_MUSIC && musicActive
                SliderRow(stringResource(label) + if (active) "  ●" else "", cur, 0..max, { "$it / $max" }) { v ->
                    runCatching { am.setStreamVolume(stream, v, 0) }
                }
            }
            Hint(stringResource(R.string.adiag_mode, modeName(am.mode)))
        }

        Card(stringResource(R.string.adiag_players)) {
            val players = remember(tick / 10) { am.activePlaybackConfigurations }
            if (players.isEmpty()) Hint(stringResource(R.string.adiag_no_player))
            for (p in players) {
                ListRow(usageName(p.audioAttributes.usage), playerInfo(context, p)) {}
            }
        }

        Card(stringResource(R.string.adiag_registers)) {
            val dsp = ServiceState.dsp
            if (dsp == null) {
                Hint(stringResource(R.string.adiag_no_chip))
            } else {
                val regs = remember(tick / 10) { REGISTERS.map { (r, n) -> Triple(r, n, dsp.shadow[r]) } }
                for ((r, n, v) in regs) {
                    BodyText("%02X  %-14s %s".format(r, n, if (v < 0) "—" else "%02X".format(v)), mono = true)
                }
            }
        }
    }
}

/** Visualizer of the global output, shared by the composition (created in DisposableEffect). */
@Volatile
private var meter: Visualizer? = null

/** Horizontal level bar, -60..0 dB, green / yellow / red. */
@Composable
private fun Meter(
    label: String,
    db: Float,
    right: String = "",
) {
    val accent = CarColors.Accent
    val track = CarColors.SurfaceHigh
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = CarColors.Text, fontSize = 16.sp, modifier = Modifier.width(170.dp))
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val f = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
            drawRoundRect(track, size = size, cornerRadius = CornerRadius(6.dp.toPx()))
            val w = size.width * f
            val yellowX = size.width * ((-12f - FLOOR_DB) / -FLOOR_DB)
            val redX = size.width * ((-3f - FLOOR_DB) / -FLOOR_DB)
            drawRoundRect(accent, size = Size(minOf(w, yellowX), size.height), cornerRadius = CornerRadius(6.dp.toPx()))
            if (w > yellowX) drawRect(YELLOW, topLeft = Offset(yellowX, 0f), size = Size(minOf(w, redX) - yellowX, size.height))
            if (w > redX) drawRect(RED, topLeft = Offset(redX, 0f), size = Size(w - redX, size.height))
        }
        Column(Modifier.width(120.dp), horizontalAlignment = Alignment.End) {
            Text(if (db <= FLOOR_DB) "—" else "%.0f dB".format(db), color = CarColors.TextDim, fontSize = 14.sp)
            if (right.isNotEmpty()) Text(right, color = CarColors.TextDim, fontSize = 12.sp)
        }
    }
}

/**
 * Test tone on one speaker: balance / fader moved to that corner (or subwoofer alone), tone played on the media
 * stream, then the user's balance / fader back.
 */
private fun speakerTest(
    client: ServiceClient,
    a: AudioState,
    target: Int,
) {
    val (balance, fade, hz) =
        when (target) {
            0 -> Triple(0, 0, 1000)
            1 -> Triple(60, 0, 1000)
            2 -> Triple(0, 60, 1000)
            3 -> Triple(60, 60, 1000)
            SUB -> Triple(a.balance, a.fade, 50)
            else -> Triple(a.balance, a.fade, 1000)
        }
    if (target in 0..3) client.call { it.setBalanceFade(balance, fade) }
    if (target == SUB && !a.subwoofer) client.call { it.setSubwoofer(true, a.subLevel) }
    try {
        playTone(hz, TEST_MS)
    } finally {
        if (target in 0..3) client.call { it.setBalanceFade(a.balance, a.fade) }
        if (target == SUB && !a.subwoofer) client.call { it.setSubwoofer(false, a.subLevel) }
        client.refreshAudio()
    }
}

/** Sine at -12 dBFS on both channels of the media stream. */
private fun playTone(
    hz: Int,
    ms: Int,
) {
    val rate = 48000
    val frames = rate * ms / 1000
    val pcm = ShortArray(frames * 2)
    val amp = 0.25 * Short.MAX_VALUE
    val ramp = rate / 50 // 20 ms fade in / out, no click
    for (i in 0 until frames) {
        val env = minOf(1.0, i / ramp.toDouble(), (frames - i) / ramp.toDouble())
        val s = (amp * env * sin(2 * PI * hz * i / rate)).toInt().toShort()
        pcm[2 * i] = s
        pcm[2 * i + 1] = s
    }
    val track =
        AudioTrack
            .Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            ).setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    try {
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(ms + 100L)
        track.stop()
    } finally {
        track.release()
    }
}

private fun playerInfo(
    context: android.content.Context,
    p: AudioPlaybackConfiguration,
): String {
    // Client uid is a system API on Android 9: read by reflection (privileged install).
    val uid = runCatching { p.javaClass.getMethod("getClientUid").invoke(p) as Int }.getOrNull()
    val app = uid?.let { context.packageManager.getPackagesForUid(it)?.firstOrNull() }
    val state =
        runCatching {
            when (p.javaClass.getMethod("getPlayerState").invoke(p) as Int) {
                2 -> "playing"
                3 -> "paused"
                4 -> "stopped"
                else -> "idle"
            }
        }.getOrDefault("")
    return listOfNotNull(app, contentName(p.audioAttributes.contentType), state.ifEmpty { null }).joinToString("  ·  ")
}

private fun usageName(u: Int) =
    when (u) {
        AudioAttributes.USAGE_MEDIA -> "Media"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "Voice call"
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "Call signalling"
        AudioAttributes.USAGE_ALARM -> "Alarm"
        AudioAttributes.USAGE_NOTIFICATION -> "Notification"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "Ringtone"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "Navigation"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "System sound"
        AudioAttributes.USAGE_ASSISTANT -> "Assistant"
        AudioAttributes.USAGE_GAME -> "Game"
        else -> "Usage $u"
    }

private fun contentName(c: Int) =
    when (c) {
        AudioAttributes.CONTENT_TYPE_MUSIC -> "music"
        AudioAttributes.CONTENT_TYPE_SPEECH -> "speech"
        AudioAttributes.CONTENT_TYPE_MOVIE -> "movie"
        AudioAttributes.CONTENT_TYPE_SONIFICATION -> "sonification"
        else -> null
    }

private fun modeName(m: Int) =
    when (m) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in call"
        AudioManager.MODE_IN_COMMUNICATION -> "in communication"
        else -> m.toString()
    }

private const val SILENCE_DB = -96f
private const val FLOOR_DB = -60f
private const val DECAY_DB = 3f
private const val METER_PERIOD_MS = 80L
private const val TEST_MS = 1500
private const val SUB = 4
private val YELLOW = Color(0xFFFDD663)
private val RED = Color(0xFFF28B82)

private val SPEAKER_TESTS =
    listOf(
        R.string.adiag_fl to 0,
        R.string.adiag_fr to 1,
        R.string.adiag_rl to 2,
        R.string.adiag_rr to 3,
        R.string.adiag_sub to SUB,
        R.string.adiag_all to 5,
    )

private val STREAMS =
    listOf(
        AudioManager.STREAM_MUSIC to R.string.adiag_stream_music,
        AudioManager.STREAM_VOICE_CALL to R.string.adiag_stream_call,
        AudioManager.STREAM_RING to R.string.adiag_stream_ring,
        AudioManager.STREAM_NOTIFICATION to R.string.adiag_stream_notification,
        AudioManager.STREAM_ALARM to R.string.adiag_stream_alarm,
        AudioManager.STREAM_SYSTEM to R.string.adiag_stream_system,
    )

private val REGISTERS =
    listOf(
        Bd37534.SETUP_1 to "setup 1",
        Bd37534.SETUP_2 to "setup 2",
        Bd37534.SETUP_3 to "setup 3",
        Bd37534.INPUT_SELECT to "input",
        Bd37534.INPUT_GAIN to "input gain",
        Bd37534.VOLUME to "volume",
        Bd37534.FADER_FRONT_1 to "fader front 1",
        Bd37534.FADER_FRONT_2 to "fader front 2",
        Bd37534.FADER_REAR_1 to "fader rear 1",
        Bd37534.FADER_REAR_2 to "fader rear 2",
        Bd37534.FADER_SUB to "fader sub",
        Bd37534.MIXING to "mixing",
        Bd37534.BASS_GAIN to "bass",
        Bd37534.MIDDLE_GAIN to "middle",
        Bd37534.TREBLE_GAIN to "treble",
        Bd37534.LOUDNESS to "loudness",
    )
