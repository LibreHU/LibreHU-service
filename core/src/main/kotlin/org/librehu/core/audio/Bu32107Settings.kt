package org.librehu.core.audio

/**
 * Settings only the ROHM BU32107 has (datasheet BU32107EFV-M Rev.001, "Select Address & Data"), all in its "Table"
 * mode: the chip computes the filters itself from these choices, no coefficient is sent.
 *
 * Frequencies are in Hz (0 = filter off, "Through"), 31.5 Hz is written 31. Delays are in tenths of a millisecond.
 */
data class Bu32107Settings(
    /** 13-band graphic EQ of the front, dB (-24..+24, 2 dB steps), bands of [Bu32107.EQ_BANDS_HZ]. */
    val eqFront: List<Int> = List(Bu32107.EQ_BANDS) { 0 },
    /** Rear EQ: same as the front when [eqRearSame], else its own gains. */
    val eqRearSame: Boolean = true,
    val eqRear: List<Int> = List(Bu32107.EQ_BANDS) { 0 },
    /** Q of every EQ band: true = 4.7 (narrow, 1/3 octave), false = 2.2 (wide). */
    val eqNarrow: Boolean = false,
    /** EQ pre-scaler -12 dB and post-scaler +12 dB: headroom inside the DSP for EQ boosts. */
    val eqHeadroom: Boolean = false,
    /** 3-band tone frequencies: bass 40/63/100/160, middle 400/630/1000/1600, treble 2500/4000/6300/10000. */
    val bassFc: Int = 100,
    val middleF0: Int = 1000,
    val trebleFc: Int = 10000,
    /** Loudness low-pass and high-pass corners, and the high boost (0, 20 or 55 = 0.2 / 0.55). */
    val loudLpf: Int = 100,
    val loudHpf: Int = 10000,
    val loudHiBoost: Int = 0,
    /** High-pass of the front and rear speakers (crossover with a subwoofer), 4th order or 2nd, phase. */
    val hpfFront: Int = 0,
    val hpfFrontSteep: Boolean = false,
    val hpfFrontInvert: Boolean = false,
    val hpfRear: Int = 0,
    val hpfRearSteep: Boolean = false,
    val hpfRearInvert: Boolean = false,
    /** Subwoofer low-pass (0 = full range) and its order, and its subsonic high-pass. */
    val subLpf: Int = 80,
    val subLpfSteep: Boolean = true,
    val subHpf: Int = 0,
    /** P2Bass (bass enhancement) of front and rear: gain 0..12 dB, frequency (0 = off). */
    val p2BassFrontDb: Int = 0,
    val p2BassFrontHz: Int = 0,
    val p2BassRearDb: Int = 0,
    val p2BassRearHz: Int = 0,
    /** Time alignment of FL, FR, RL, RR and subwoofer, tenths of ms (0..[Bu32107.DELAY_MAX_TENTHS]). */
    val delays: List<Int> = List(5) { 0 },
) {
    fun normalized(): Bu32107Settings =
        copy(
            eqFront = gains(eqFront),
            eqRear = gains(eqRear),
            bassFc = pick(Bu32107.BASS_FC, bassFc),
            middleF0 = pick(Bu32107.MIDDLE_F0, middleF0),
            trebleFc = pick(Bu32107.TREBLE_FC, trebleFc),
            loudLpf = pick(Bu32107.LOUD_LPF, loudLpf),
            loudHpf = pick(Bu32107.LOUD_HPF, loudHpf),
            loudHiBoost = pick(Bu32107.LOUD_HIBOOST, loudHiBoost),
            hpfFront = pick(Bu32107.HPF_FC, hpfFront),
            hpfRear = pick(Bu32107.HPF_FC, hpfRear),
            subLpf = pick(Bu32107.SUB_LPF_FC, subLpf),
            subHpf = pick(Bu32107.SUB_HPF_FC, subHpf),
            p2BassFrontDb = p2BassFrontDb.coerceIn(0, Bu32107.P2BASS_MAX_DB),
            p2BassFrontHz = pick(Bu32107.P2BASS_FC, p2BassFrontHz),
            p2BassRearDb = p2BassRearDb.coerceIn(0, Bu32107.P2BASS_MAX_DB),
            p2BassRearHz = pick(Bu32107.P2BASS_FC, p2BassRearHz),
            delays = List(5) { delays.getOrElse(it) { 0 }.coerceIn(0, Bu32107.DELAY_MAX_TENTHS) },
        )

    /** One line, `key=value` pairs separated by `;`, lists with `,` (stored in the service's preferences). */
    fun encode(): String =
        listOf(
            "eqF" to eqFront.joinToString(","),
            "eqRS" to eqRearSame,
            "eqR" to eqRear.joinToString(","),
            "eqN" to eqNarrow,
            "eqH" to eqHeadroom,
            "bass" to bassFc,
            "mid" to middleF0,
            "treb" to trebleFc,
            "lLpf" to loudLpf,
            "lHpf" to loudHpf,
            "lHb" to loudHiBoost,
            "hF" to hpfFront,
            "hFs" to hpfFrontSteep,
            "hFi" to hpfFrontInvert,
            "hR" to hpfRear,
            "hRs" to hpfRearSteep,
            "hRi" to hpfRearInvert,
            "sL" to subLpf,
            "sLs" to subLpfSteep,
            "sH" to subHpf,
            "pFd" to p2BassFrontDb,
            "pFh" to p2BassFrontHz,
            "pRd" to p2BassRearDb,
            "pRh" to p2BassRearHz,
            "dl" to delays.joinToString(","),
        ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        private fun gains(list: List<Int>) =
            List(Bu32107.EQ_BANDS) { i -> (list.getOrElse(i) { 0 }.coerceIn(-Bu32107.EQ_MAX_DB, Bu32107.EQ_MAX_DB) / 2) * 2 }

        /** Nearest allowed value (0 = off stays 0 when allowed). */
        internal fun pick(
            allowed: IntArray,
            v: Int,
        ): Int = allowed.minByOrNull { kotlin.math.abs(it - v) } ?: v

        /** Settings from [encode]; unknown or broken fields keep their default. */
        fun decode(text: String?): Bu32107Settings {
            val d = Bu32107Settings()
            if (text.isNullOrBlank()) return d
            val m = text.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

            fun i(
                k: String,
                def: Int,
            ) = m[k]?.toIntOrNull() ?: def

            fun b(
                k: String,
                def: Boolean,
            ) = m[k]?.toBooleanStrictOrNull() ?: def

            fun l(
                k: String,
                def: List<Int>,
            ) = m[k]?.split(',')?.map { it.toIntOrNull() ?: 0 } ?: def
            return Bu32107Settings(
                eqFront = l("eqF", d.eqFront),
                eqRearSame = b("eqRS", d.eqRearSame),
                eqRear = l("eqR", d.eqRear),
                eqNarrow = b("eqN", d.eqNarrow),
                eqHeadroom = b("eqH", d.eqHeadroom),
                bassFc = i("bass", d.bassFc),
                middleF0 = i("mid", d.middleF0),
                trebleFc = i("treb", d.trebleFc),
                loudLpf = i("lLpf", d.loudLpf),
                loudHpf = i("lHpf", d.loudHpf),
                loudHiBoost = i("lHb", d.loudHiBoost),
                hpfFront = i("hF", d.hpfFront),
                hpfFrontSteep = b("hFs", d.hpfFrontSteep),
                hpfFrontInvert = b("hFi", d.hpfFrontInvert),
                hpfRear = i("hR", d.hpfRear),
                hpfRearSteep = b("hRs", d.hpfRearSteep),
                hpfRearInvert = b("hRi", d.hpfRearInvert),
                subLpf = i("sL", d.subLpf),
                subLpfSteep = b("sLs", d.subLpfSteep),
                subHpf = i("sH", d.subHpf),
                p2BassFrontDb = i("pFd", d.p2BassFrontDb),
                p2BassFrontHz = i("pFh", d.p2BassFrontHz),
                p2BassRearDb = i("pRd", d.p2BassRearDb),
                p2BassRearHz = i("pRh", d.p2BassRearHz),
                delays = l("dl", d.delays),
            ).normalized()
        }
    }
}
