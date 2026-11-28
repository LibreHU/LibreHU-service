package org.librehu.service.hw

import android.content.Context
import org.librehu.core.audio.Ak7604Program
import java.security.MessageDigest

/** Jancar's AK7604 DSP program shipped in assets/ak7604 (see its README), checked against the analysed hashes. */
object Ak7604Assets {
    private val HASHES =
        mapOf(
            "regs.bin" to "ebd4f7a893a52112220260fb17ef58f3063ce01e4d5e9781b08fed24a6419c91",
            "pram.bin" to "36459b3728e07b501274ce8a7d586fe81ecf7b3a4b967b738a0e9804744845bb",
            "cram.bin" to "1ffce26d2a75d06805c5f20fe65c8fca08a92c564fd12f88abb91d67a4779460",
        )

    /** The program, or null when a file is missing or differs from the one the driver was written for. */
    fun load(context: Context): Ak7604Program? {
        val files =
            HASHES.mapValues { (name, hash) ->
                val bytes = runCatching { context.assets.open("ak7604/$name").use { it.readBytes() } }.getOrNull() ?: return null
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (sha != hash) return null
                bytes
            }
        return Ak7604Program(files.getValue("regs.bin"), files.getValue("pram.bin"), files.getValue("cram.bin")).takeIf { it.valid }
    }
}
