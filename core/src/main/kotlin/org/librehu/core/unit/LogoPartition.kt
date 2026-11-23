package org.librehu.core.unit

/**
 * Header of the `logo` partition of the UJC201 (Autochips MRF format), as the boot loader reads it.
 *
 * Map (UJC201 dumps, 22/11/2026): 0x0 MediaTek header; 0x200 `MRFv1.1.1` and its version string at 0x218; 0x300 panel
 * block (`8257Pa`, width 0x318, height 0x31C); 0x500 logo descriptor (`Logo`, byte size 0x508, width 0x510, height
 * 0x514, bits per pixel 0x518, offset of the default copy 0x524); 0xE00 `[Lvds_Init_S]`, the LVDS bridge start-up
 * table; pixels of the logo from 0x1200 (Autochips `writelogo(2, …)`, BGRA, top row first).
 *
 * The boot loader draws the logo with the panel size of the MetaZone (0x101DD / 0x101DE), not with this header: an
 * header that disagrees with the panel (seen on a unit: header 1024x600, panel and pixels 720x1280) is left as it is,
 * and LibreHU writes no logo on it.
 */
data class LogoHeader(
    val mrfVersion: String?,
    val panelWidth: Int,
    val panelHeight: Int,
    val hasDescriptor: Boolean,
    val logoBytes: Int,
    val logoWidth: Int,
    val logoHeight: Int,
    val logoBpp: Int,
    val defaultCopy: Int,
    val hasLvdsTable: Boolean,
) {
    companion object {
        const val PIXELS_OFFSET = 0x1200

        /** Bytes needed by [parse]. */
        const val HEADER_BYTES = 0x1000

        private fun u32(
            b: ByteArray,
            o: Int,
        ): Int =
            if (o + 4 > b.size) {
                0
            } else {
                (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            }

        private fun ascii(
            b: ByteArray,
            o: Int,
            max: Int,
        ): String? {
            if (o >= b.size) return null
            val end = (o until minOf(b.size, o + max)).firstOrNull { b[it] == 0.toByte() } ?: minOf(b.size, o + max)
            val s = String(b, o, end - o, Charsets.ISO_8859_1)
            return s.takeIf { it.isNotEmpty() && it.all { c -> c in ' '..'~' } }
        }

        private fun starts(
            b: ByteArray,
            o: Int,
            text: String,
        ): Boolean = o + text.length <= b.size && text.indices.all { b[o + it] == text[it].code.toByte() }

        /** Reads the first [HEADER_BYTES] bytes of the partition; null when it is not an MRF logo partition. */
        fun parse(b: ByteArray): LogoHeader? {
            if (!starts(b, 0x200, "MRF")) return null
            val mrf = ascii(b, 0x200, 16)
            val version = ascii(b, 0x218, 16)
            val desc = starts(b, 0x500, "Logo")
            return LogoHeader(
                mrfVersion = listOfNotNull(mrf, version).joinToString(" "),
                panelWidth = if (starts(b, 0x300, "8257")) u32(b, 0x318) else 0,
                panelHeight = if (starts(b, 0x300, "8257")) u32(b, 0x31C) else 0,
                hasDescriptor = desc,
                logoBytes = if (desc) u32(b, 0x508) else 0,
                logoWidth = if (desc) u32(b, 0x510) else 0,
                logoHeight = if (desc) u32(b, 0x514) else 0,
                logoBpp = if (desc) u32(b, 0x518) else 0,
                defaultCopy = if (desc) u32(b, 0x524) else 0,
                hasLvdsTable = starts(b, 0xE00, "[Lvds_Init_S]"),
            )
        }

        /** Parses `od -An -tx1 -v` output (hex bytes separated by spaces and new lines). */
        fun parseOd(text: String): LogoHeader? {
            val bytes =
                text
                    .split(' ', '\n', '\t', '\r')
                    .filter { it.isNotEmpty() }
                    .mapNotNull { it.toIntOrNull(16)?.toByte() }
                    .toByteArray()
            return parse(bytes)
        }
    }
}

/** How a new boot logo is written, or why it is not. */
data class LogoPlan(
    /** Size of the pixel buffer, in the panel's own orientation (what the boot loader draws). */
    val width: Int,
    val height: Int,
    /** Clockwise rotation from the picture as the driver sees it to the panel buffer. */
    val rotation: Int,
    /** Null when the logo can be written; else the reason (a code shown to the user). */
    val refused: String?,
) {
    companion object {
        private fun sane(v: Int?) = v != null && v in 240..4096

        /**
         * Panel size: the MetaZone's ([mzWidth] / [mzHeight], used by the boot loader) when valid, else the header's
         * descriptor. Refused: no MRF header or no logo descriptor (`logo_header`), not 32 bits per pixel
         * (`logo_bpp:<n>`), descriptor size different from the panel (`logo_header_size:<w>x<h>/<W>x<H>`), pixels that
         * would run into the default copy (`logo_space`), no usable size (`logo_size`).
         * [rotation]: the MetaZone's 0x10033 as ATC Settings reads it on the AC8257 (90 / 270: the panel is mounted
         * the other way), else `persist.vendor.hwc.hwrotation`; plus the user's extra turn.
         */
        fun plan(
            header: LogoHeader?,
            mzWidth: Int?,
            mzHeight: Int?,
            mzRotation: Int?,
            propRotation: Int?,
            extraRotation: Int = 0,
        ): LogoPlan {
            val base = listOf(mzRotation, propRotation).firstOrNull { it != null && it in setOf(0, 90, 180, 270) } ?: 0
            val rotation = ((base + extraRotation) % 360 + 360) % 360
            val (w, h) =
                when {
                    sane(mzWidth) && sane(mzHeight) -> mzWidth!! to mzHeight!!
                    header != null && sane(header.logoWidth) && sane(header.logoHeight) -> header.logoWidth to header.logoHeight
                    else -> 0 to 0
                }
            fun refuse(why: String) = LogoPlan(w, h, rotation, why)
            if (header == null || !header.hasDescriptor) return refuse("logo_header")
            if (header.logoBpp != 32) return refuse("logo_bpp:${header.logoBpp}")
            if (w == 0) return refuse("logo_size")
            if (header.logoWidth != w || header.logoHeight != h) {
                return refuse("logo_header_size:${header.logoWidth}x${header.logoHeight}/${w}x$h")
            }
            val end = LogoHeader.PIXELS_OFFSET.toLong() + w.toLong() * h * 4
            if (header.defaultCopy > 0 && end > header.defaultCopy) return refuse("logo_space")
            return LogoPlan(w, h, rotation, null)
        }
    }
}
