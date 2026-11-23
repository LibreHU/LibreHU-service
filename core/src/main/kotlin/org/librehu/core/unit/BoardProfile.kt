package org.librehu.core.unit

import java.io.File

/** Sound processor of a UJC201 (AC8257) board. */
enum class AudioChip(
    val label: String,
) {
    BD37534("ROHM BD37534"),
    BU32107("ROHM BU32107"),
    AK7604("AKM AK7604"),
}

/**
 * Audio wiring of one UJC201 (Autochips AC8257) board, as Jancar's ivi-services sets it for that board
 * (`Platform.identifyBoardTypeAC8257` and the `Platform_*` classes of `com.jancar.services.platform.AC8257`, ivi-services
 * 3.0.0 of firmware UJC201-V1.1.68R1-260729, disassembled 23/11/2026).
 *
 * Inputs are the sound processor's physical inputs; gains in dB are ivi-services' `initAudioInputChannelGain` values for
 * them; [subBaseDb] its `setSubWooferBaseValue`; [iviConfigSection] the section of `/jancar/config/ivi-config.ini` it
 * reads the per-source pre-volumes from.
 */
data class BoardProfile(
    /** Board id as the kernel shows it (`A0_AN`...), null when unknown. */
    val boardId: String?,
    /** ivi-services class for this board (for diagnostics). */
    val platform: String,
    /** Null when ivi-services has no platform for this id (A1 boards with a 4th character B). */
    val chip: AudioChip?,
    val i2cBus: Int,
    val resetGpio: Int,
    val androidInput: Int,
    val radioInput: Int,
    val auxInput: Int,
    val avInput: Int,
    val androidGainDb: Int,
    val radioGainDb: Int,
    val auxGainDb: Int,
    val avGainDb: Int,
    val subBaseDb: Int?,
    val iviConfigSection: String?,
    /** Tuner from the 5th character: "T", "W" or null (no tuner chip, MediaTek FM). */
    val tuner: String?,
) {
    /** The board id was read and is in the kernel's table (else defaults of the most common board). */
    val known: Boolean get() = boardId != null
}

object BoardProfiles {
    /** Node of the kernel (stock kernel, LibreHU kernel since 23/11/2026) with the board id, "NULL" when unknown. */
    const val BOARD_ID_NODE = "/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id"

    /** Raw AUXADC values of the two board id channels ("ch2 ch3"), for a board missing from the kernel's table. */
    const val BOARD_ADC_NODE = "/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_adc"

    private const val BASE_BUS = 6
    private const val BASE_RESET_GPIO = 168

    private fun bd37534(
        id: String?,
        platform: String,
        androidGain: Int,
        radioGain: Int,
        subBase: Int,
        section: String,
    ) = BoardProfile(
        boardId = id,
        platform = platform,
        chip = AudioChip.BD37534,
        i2cBus = BASE_BUS,
        resetGpio = BASE_RESET_GPIO,
        androidInput = 11,
        radioInput = 3,
        auxInput = 0,
        avInput = 0,
        androidGainDb = androidGain,
        radioGainDb = radioGain,
        auxGainDb = 5,
        avGainDb = 5,
        subBaseDb = subBase,
        iviConfigSection = section,
        tuner = tuner(id),
    )

    private fun bu32107(
        id: String?,
        platform: String,
    ) = BoardProfile(
        boardId = id,
        platform = platform,
        chip = AudioChip.BU32107,
        i2cBus = BASE_BUS,
        resetGpio = BASE_RESET_GPIO,
        androidInput = 16,
        radioInput = 3,
        auxInput = 4,
        avInput = 4,
        androidGainDb = 0,
        radioGainDb = 11,
        auxGainDb = 5,
        avGainDb = 5,
        subBaseDb = -18,
        iviConfigSection = "Audio_A1",
        tuner = tuner(id),
    )

    private fun ak7604(id: String?) =
        BoardProfile(
            boardId = id,
            platform = "Platform_AutoChips_8257A1_Ak7604",
            chip = AudioChip.AK7604,
            i2cBus = 5,
            resetGpio = 14,
            androidInput = 16,
            radioInput = 6,
            auxInput = 1,
            avInput = 1,
            androidGainDb = 3,
            radioGainDb = 4,
            auxGainDb = -2,
            avGainDb = -2,
            subBaseDb = null,
            iviConfigSection = "Audio_A1",
            tuner = tuner(id),
        )

    private fun tuner(id: String?): String? =
        when (id?.getOrNull(4)) {
            'T' -> "T"
            'W' -> "W"
            else -> null
        }

    /**
     * Profile of a board id, with ivi-services' rule: by the first two characters (A0, A1, B0, C0, C1, D0) and the
     * 4th (sound processor): A1 + A -> BD37534 (A1 gains), A1 + B -> none, A1 + other -> AK7604; D0 + A -> BD37534,
     * D0 + other -> BU32107; any other + A -> BD37534, + other -> BU32107. Unknown or unreadable id: the A0_AN
     * profile (BD37534), what LibreHU assumed before the kernel gave the id.
     */
    fun forBoardId(raw: String?): BoardProfile {
        val id = raw?.trim()?.takeIf { it.length >= 4 && it != "NULL" }
        if (id == null) return bd37534(null, "Platform_AutoChips_8257_37534", 4, 2, -10, "Audio_A0_AN")
        val prefix = id.substring(0, 2)
        val chip = id[3]
        return when (prefix) {
            "A1" ->
                when (chip) {
                    'A' -> bd37534(id, "Platform_AutoChips_8257A1_37534", 5, 13, -5, "Audio_A1")
                    'B' -> bd37534(id, "none", 4, 2, -10, "Audio_A0_AN").copy(chip = null)
                    else -> ak7604(id)
                }
            "D0" ->
                if (chip == 'A') {
                    bd37534(id, "Platform_Ac8257D_37534", 4, 2, -10, "Audio_A0_AN")
                } else {
                    bu32107(id, "Platform_Ac8257D_32107")
                }
            else ->
                if (chip == 'A') {
                    bd37534(id, "Platform_AutoChips_8257_37534", 4, 2, -10, "Audio_A0_AN")
                } else {
                    bu32107(id, "Platform_AutoChips_8257_32107")
                }
        }
    }

    /** Board id from the kernel, null when the node is missing (older kernel) or says NULL. */
    fun readBoardId(node: File = File(BOARD_ID_NODE)): String? =
        runCatching { node.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() && it != "NULL" }

    /** Raw values of the two board id channels, null when unreadable. */
    fun readBoardAdc(node: File = File(BOARD_ADC_NODE)): String? = runCatching { node.readText().trim() }.getOrNull()

    fun current(): BoardProfile = forBoardId(readBoardId())
}
