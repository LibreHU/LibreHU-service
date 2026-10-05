package org.librehu.service.bt

import android.util.Log
import java.lang.reflect.Method

/**
 * The car Bluetooth profiles (`BluetoothHeadsetClient`, `BluetoothA2dpSink`, `BluetoothPbapClient`) are hidden APIs on
 * Android 9. Their objects are obtained normally (`BluetoothAdapter.getProfileProxy`), their methods by reflection.
 *
 * Android 9 restricts reflection on hidden APIs; [exempt] lifts it for this process the way the platform's own tools
 * do (`VMRuntime.setHiddenApiExemptions`, reached through `Class.getDeclaredMethod` so that the caller is the boot
 * class path). This works up to Android 10, which is all this head unit needs. Alternative without code:
 * `adb shell settings put global hidden_api_policy_p_apps 1`.
 */
internal object HiddenApi {
    private const val TAG = "LibreHU-BT"

    @Volatile
    private var exempted = false

    fun exempt() {
        if (exempted) return
        exempted =
            try {
                val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod =
                    Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
                val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
                val setExemptions =
                    getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
                setExemptions.invoke(getRuntime.invoke(null), *arrayOf<Any?>(arrayOf("L")))
                true
            } catch (e: Throwable) {
                Log.w(TAG, "Hidden API exemption failed (set hidden_api_policy_p_apps=1): $e")
                false
            }
    }

    private val cache = HashMap<String, Method?>()

    /** Public or hidden method of [cls] (or of a superclass), cached; null when it does not exist. */
    fun method(
        cls: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): Method? {
        val key = cls.name + "#" + name + params.joinToString(",", "(", ")") { it.name }
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val m =
            try {
                cls.getMethod(name, *params)
            } catch (_: NoSuchMethodException) {
                var c: Class<*>? = cls
                var found: Method? = null
                while (c != null && found == null) {
                    found =
                        try {
                            c.getDeclaredMethod(name, *params).apply { isAccessible = true }
                        } catch (_: NoSuchMethodException) {
                            null
                        }
                    c = c.superclass
                }
                found
            }
        synchronized(cache) { cache[key] = m }
        if (m == null) Log.w(TAG, "Missing method $key")
        return m
    }

    /** Calls [name] on [target]; returns null when the method is missing or throws. */
    fun call(
        target: Any?,
        name: String,
        types: Array<Class<*>>,
        vararg args: Any?,
    ): Any? {
        if (target == null) return null
        val m = method(target.javaClass, name, *types) ?: return null
        return try {
            m.invoke(target, *args)
        } catch (e: Throwable) {
            Log.w(TAG, "$name failed: ${e.cause ?: e}")
            null
        }
    }

    fun classOrNull(name: String): Class<*>? =
        try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            null
        }
}
