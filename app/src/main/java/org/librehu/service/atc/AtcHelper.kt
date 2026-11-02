package org.librehu.service.atc

import java.io.File

/**
 * Runs as root in its own process (`CLASSPATH=<LibreHU apk> app_process / org.librehu.service.atc.AtcHelper …`, see
 * [AtcTools]): calls Autochips' framework (`/system/framework/autochips.jar`, on the boot class path) where the app's
 * SELinux domain cannot reach the metazone (`/dev/mtz`) nor the logo partition. One line per result on stdout.
 *
 * - `get <addr>…` → `<addr>=<value>` (`AtcMetazone.readval`)
 * - `set <addr>=<value>…` → writes them, then `AtcMetazone.flush(true)`
 * - `arm2` → `new BackCar().updateArm2MdpSetting()`: the ARM2 fast reverse takes the metazone settings
 * - `arm2ready` → `before=<status>`, then when the ARM2 has not handed the display over (status 2):
 *   `ready=<BackCar.sendReadyToArm2()>` and `after=<status>` once it is 2 or after [ARM2_WAIT_MS] (what Jancar's
 *   hd2cam does at start-up; without it hardware-decoded video stays green, docs/atc.md)
 * - `logo <file>` → `AtcLogo.writelogo(2, 0, size, data)` with the file's BGRA pixels (screen size, top row first)
 */
object AtcHelper {
    private const val METAZONE = "com.autochips.metazone.AtcMetazone"
    private const val BACKCAR = "com.autochips.backcar.BackCar"
    private const val LOGO = "com.autochips.logo.AtcLogo"

    /** ARM2 status once it has exited and handed the display over to Android. */
    private const val ARM2_HANDED_OVER = 2
    private const val ARM2_WAIT_MS = 20_000L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.firstOrNull()) {
                "get" -> {
                    val read = cls(METAZONE).getMethod("readval", Int::class.javaPrimitiveType, IntArray::class.java)
                    for (a in args.drop(1)) {
                        val out = IntArray(4)
                        read.invoke(null, a.toInt(), out)
                        println("$a=${out[0]}")
                    }
                }

                "set" -> {
                    val c = cls(METAZONE)
                    val write = c.getMethod("writeval", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    for (pair in args.drop(1)) {
                        val (a, v) = pair.split('=')
                        println("$a=${write.invoke(null, a.toInt(), v.toInt())}")
                    }
                    c.getMethod("flush", Boolean::class.javaPrimitiveType).invoke(null, true)
                    println("ok")
                }

                "arm2" -> {
                    val c = cls(BACKCAR)
                    c.getMethod("updateArm2MdpSetting").invoke(c.getDeclaredConstructor().newInstance())
                    println("ok")
                }

                "arm2ready" -> {
                    val c = cls(BACKCAR)
                    val backcar = c.getDeclaredConstructor().newInstance()
                    val status = c.getMethod("getArm2Status", IntArray::class.java)

                    fun read(): Int {
                        val v = intArrayOf(-1)
                        return if (status.invoke(backcar, v) == true) v[0] else -1
                    }
                    var now = read()
                    println("before=$now")
                    if (now != ARM2_HANDED_OVER) {
                        println("ready=${c.getMethod("sendReadyToArm2").invoke(backcar)}")
                        val deadline = System.currentTimeMillis() + ARM2_WAIT_MS
                        while (now != ARM2_HANDED_OVER && System.currentTimeMillis() < deadline) {
                            Thread.sleep(500)
                            now = read()
                        }
                    }
                    println("after=$now")
                    runCatching { c.getMethod("release").invoke(backcar) }
                }

                "logo" -> {
                    val data = File(args[1]).readBytes()
                    val write =
                        cls(LOGO).getMethod(
                            "writelogo",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            ByteArray::class.java,
                        )
                    println("logo=${write.invoke(null, 2, 0, data.size, data)}")
                }

                else -> {
                    println("error=usage: get|set|arm2|arm2ready|logo")
                }
            }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            println("error=${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun cls(name: String): Class<*> = Class.forName(name)
}
