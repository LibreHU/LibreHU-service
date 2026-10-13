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
 * - `logo <file>` → `AtcLogo.writelogo(2, 0, size, data)` with the file's BGRA pixels (screen size, top row first)
 */
object AtcHelper {
    private const val METAZONE = "com.autochips.metazone.AtcMetazone"
    private const val BACKCAR = "com.autochips.backcar.BackCar"
    private const val LOGO = "com.autochips.logo.AtcLogo"

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
                    println("error=usage: get|set|arm2|logo")
                }
            }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            println("error=${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun cls(name: String): Class<*> = Class.forName(name)
}
