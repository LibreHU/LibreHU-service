package org.librehu.core.can

import java.io.File
import kotlin.system.exitProcess

/**
 * `librehu-car-tool.jar`: the car data engine of LibreHU service on a computer, to write and check car profiles
 * without the car (the profiles repository runs it on every change).
 *
 * - `version`: the engine version (Semantic Versioning, [CanEngine]).
 * - `check <file or folder>…`: reads every profile (`extends` resolved among the built-in ones and those given), runs
 *   its `examples`, checks its `"engine"` against what it uses; exit code 1 on any problem.
 * - `decode <profile> <CAN export>`: decodes each message of an export of the CAN tab (`HH:MM:SS.mmm  CC: DD DD…`)
 *   with the profile, one line per message that gives values.
 */
object CarTool {
    @JvmStatic
    fun main(args: Array<String>) {
        // UTF-8 whatever the computer's locale (°, ·, accents of the labels).
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
        val code =
            when (args.firstOrNull()) {
                "version" -> {
                    println(CanEngine.VERSION)
                    0
                }
                "check" -> check(args.drop(1), System.out::println)
                "decode" -> if (args.size == 3) decode(File(args[1]), File(args[2]), System.out::println) else usage()
                "pack" -> if (args.size in 2..3) pack(File(args[1]), args.getOrNull(2), System.out::println) else usage()
                else -> usage()
            }
        exitProcess(code)
    }

    private fun usage(): Int {
        System.err.println("librehu-car-tool ${CanEngine.VERSION}: version | check <file or folder>... | decode <profile.json> <can-export.txt> | pack <make.json> [sha256]")
        return 2
    }

    /** Profile files under [paths] (folders: every .json below them). */
    fun files(paths: List<String>): List<File> =
        paths.flatMap { p ->
            val f = File(p)
            if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList() else listOf(f)
        }

    /** Checks the profiles; returns the exit code (0 = all good). */
    fun check(
        paths: List<String>,
        out: (String) -> Unit,
    ): Int {
        val list = files(paths)
        if (list.isEmpty()) {
            out("no profile found")
            return 1
        }
        // Parents named by "extends": the built-in profiles, and the files given (by their id).
        val byId = HashMap<String, String>()
        for (f in list) {
            val t = f.readText()
            Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let { byId.putIfAbsent(it.groupValues[1], t) }
        }
        val resolve: (String) -> String? = { id -> byId[id] ?: CanVehicles.builtInText(id) }
        var bad = 0
        val ids = HashMap<String, File>()
        for (f in list) {
            val problems = mutableListOf<String>()
            val text = f.readText()
            try {
                val v = CanVehicles.fromJson(text, resolve)
                ids[v.id]?.let { problems += "id ${v.id} also in ${it.path}" }
                ids[v.id] = f
                problems += v.selfTest()
                val needed = CanEngine.requiredBy(v)
                val declared = Regex("\"engine\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                when {
                    declared == null -> problems += "no \"engine\": write \"engine\": \"$needed\""
                    lower(declared, needed) -> problems += "\"engine\": \"$declared\" but the profile uses what $needed brought: write \"engine\": \"$needed\""
                }
                if (v.examples.isEmpty()) problems += "no \"examples\": add at least one message and the values it gives"
                out((if (problems.isEmpty()) "ok    " else "FAIL  ") + "${f.path}  ${v.id}  ${v.variants.size} cars  engine $needed")
            } catch (e: CanVehicleException) {
                problems += e.message ?: "unreadable"
                out("FAIL  ${f.path}")
            }
            problems.forEach { out("      - $it") }
            if (problems.isNotEmpty()) bad++
        }
        out("${list.size - bad} of ${list.size} profiles good (engine ${CanEngine.VERSION})")
        return if (bad == 0) 0 else 1
    }

    /** True when the requirement [declared] asks for less than [needed] (both `>=x.y`). */
    private fun lower(
        declared: String,
        needed: String,
    ): Boolean {
        val d = CanEngine.parse(declared.trim().removePrefix(">=").removePrefix("^")) ?: return true
        val n = CanEngine.parse(needed.removePrefix(">=")) ?: return false
        return d < n
    }

    /** Reads a pack as LibreHU service installs it (all or nothing); returns the exit code. */
    fun pack(
        file: File,
        sha256: String?,
        out: (String) -> Unit,
    ): Int =
        try {
            val p = CanPacks.read(file.readBytes(), sha256)
            out("ok    pack ${p.id} revision ${p.revision}, engine ${p.engine}: ${p.profiles.size} profiles, ${p.profiles.sumOf { it.variants.size }} cars")
            0
        } catch (e: CanVehicleException) {
            out("FAIL  ${file.path}: ${e.message}")
            1
        }

    /** Decodes a CAN export with a profile; returns the exit code. */
    fun decode(
        profile: File,
        export: File,
        out: (String) -> Unit,
    ): Int {
        val v =
            try {
                CanVehicles.fromJson(profile.readText())
            } catch (e: CanVehicleException) {
                out("profile: ${e.message}")
                return 1
            }
        val line = Regex("""^(\S+)\s+([0-9A-Fa-f]{2}):((?:\s+[0-9A-Fa-f]{2})*)\s*(\(bad checksum\))?""")
        val roles = HashMap<String, Double>()
        var n = 0
        export.forEachLine { l ->
            val m = line.find(l) ?: return@forEachLine
            if (m.groupValues[4].isNotEmpty()) return@forEachLine
            val data = m.groupValues[3].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            val f = CanboxFrame(m.groupValues[2].toInt(16), data, true)
            val values = v.decode(f) { roles[it] }
            values.forEach { x -> x.role?.let { r -> (x.number ?: x.flag?.let { if (it) 1.0 else 0.0 })?.let { roles[r] = it } } }
            if (values.isEmpty()) return@forEachLine
            n++
            out("${m.groupValues[1]}  ${v.describe(f)}")
        }
        out("$n messages decoded")
        return 0
    }
}
