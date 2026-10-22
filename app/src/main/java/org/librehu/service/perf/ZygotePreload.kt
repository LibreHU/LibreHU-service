package org.librehu.service.perf

import org.librehu.service.root.RootShell

/**
 * `ro.zygote.preload.enable` of the Jancar ROM (0 in its build.prop): not an AOSP property (Android 9's and 10's
 * ZygoteInit do not read it), added by MediaTek / Autochips / Jancar. 0 most likely skips Zygote's class preload: a
 * faster boot, but every app then loads Android's classes itself at its start (slower openings). A `ro.` property
 * cannot change while Android runs: it is set through a root module's `system.prop` (Magisk and KernelSU load it at
 * boot with resetprop, kernelsu.org "Module guide"), `/data/adb/modules/librehu_props`, so /system is not touched and
 * removing the module gives the ROM's value back. Takes effect at the next start.
 */
object ZygotePreload {
    const val PROP = "ro.zygote.preload.enable"
    private const val MODULE = "/data/adb/modules/librehu_props"

    /** Value now (Android's), empty when the property is not set. */
    fun current(): String =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, PROP, "") as String
        }.getOrDefault("")

    /** Root manager with modules (Magisk, KernelSU, APatch): `/data/adb/modules` exists. */
    fun modulesAvailable(): Boolean = RootShell.run("[ -d /data/adb/modules ] && echo yes")?.contains("yes") == true

    /** Value set for the next start by the module, null = none (the ROM's value). */
    fun next(): String? =
        RootShell
            .run("cat $MODULE/system.prop 2>/dev/null")
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("$PROP=") }
            ?.substringAfter('=')
            ?.trim()

    /** Sets the value for the next start ("1" / "0"), or null to remove the module (the ROM's value). */
    fun set(value: String?): Boolean {
        if (value == null) return RootShell.run("rm -rf $MODULE && echo ok")?.contains("ok") == true
        if (value != "0" && value != "1") return false
        val script =
            """
            mkdir -p $MODULE &&
            printf 'id=librehu_props\nname=LibreHU properties\nversion=1\nversionCode=1\nauthor=LibreHU\ndescription=Properties set by LibreHU-service (Tweaks): $PROP\n' > $MODULE/module.prop &&
            printf '$PROP=$value\n' > $MODULE/system.prop &&
            rm -f $MODULE/disable $MODULE/remove &&
            chmod 644 $MODULE/module.prop $MODULE/system.prop &&
            echo ok
            """.trimIndent()
        return RootShell.run(script)?.contains("ok") == true
    }
}
