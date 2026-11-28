package org.librehu.core.audio

/**
 * What [org.librehu.core.unit.HeadUnit] drives on any sound processor of a UJC201 board ([Bd37534], [Bu32107]).
 * Settings a chip cannot do are ignored by it (for example the BD37534 tone frequencies on a BU32107, which has its own
 * in [Bu32107Settings]).
 */
interface SoundProcessor {
    /** Chip name for logs and the apps ("BD37534", "BU32107"). */
    val chip: String

    /** Power-on sequence; the chip is left muted on the Android input. */
    fun init(): Boolean

    val isMuted: Boolean

    fun setMute(on: Boolean): Boolean

    /** Android's audio as the source, with the board's Android input gain (dB). */
    fun selectAndroid(gainDb: Int): Boolean

    /** The AUX / AV jack as the source. */
    fun selectAux(): Boolean

    fun setInputGain(gainDb: Int): Boolean

    /** Master volume step 0..[Bd37534.MAX_VOLUME] of [Bd37534.VOLUME_CURVE], plus an offset in dB. */
    fun setVolume(
        step: Int,
        offsetDb: Int = 0,
    ): Boolean

    /** Android mixed over AUX at the volume [step] plus [offsetDb]; null = off. */
    fun setMixing(
        step: Int?,
        offsetDb: Int = 0,
    ): Boolean

    /** Bass / middle / treble gains, dB (each chip rounds and clamps to its own steps). */
    fun setToneDb(
        bassDb: Int,
        middleDb: Int,
        trebleDb: Int,
    ): Boolean

    fun setToneSetup(setup: ToneSetup): Boolean

    /** Balance and fader 0..60 (30 = centre) plus each speaker's own level FL, FR, RL, RR (dB). */
    fun setBalanceFade(
        balance: Int,
        fade: Int,
        trimsDb: IntArray = IntArray(4),
    ): Boolean

    fun setLoudness(
        gainDb: Int,
        freqHz: Int,
        hicut: Int,
    ): Boolean

    /** Subwoofer on / off, its level 0..[Bd37534.SUB_MAX_LEVEL] (level - 5 = dB) and filter. */
    fun setSubwoofer(
        on: Boolean,
        level: Int,
        config: SubConfig,
    ): Boolean
}
