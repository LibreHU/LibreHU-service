package org.librehu.service.net.cable

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.IBinder

/**
 * Root helper (`CLASSPATH=<LibreHU apk> app_process / org.librehu.service.net.cable.UsbGrant <device name> <uid>`):
 * gives LibreHU-service the use of the phone's USB device without the dialog Android shows each time it is plugged in
 * (an Android Auto phone comes back as a new device after each switch). Root passes the MANAGE_USB check of
 * IUsbManager.grantDevicePermission (hidden API, reflection). Prints `granted` on success.
 */
object UsbGrant {
    @JvmStatic
    fun main(args: Array<String>) {
        val name = args[0]
        val uid = args[1].toInt()
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "usb") as IBinder
        val usb = Class.forName("android.hardware.usb.IUsbManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val devices = Bundle()
        usb.javaClass.getMethod("getDeviceList", Bundle::class.java).invoke(usb, devices)
        devices.classLoader = UsbDevice::class.java.classLoader
        @Suppress("DEPRECATION")
        val device = devices.getParcelable<UsbDevice>(name) ?: throw IllegalStateException("no USB device $name")
        usb.javaClass.getMethod("grantDevicePermission", UsbDevice::class.java, Int::class.javaPrimitiveType).invoke(usb, device, uid)
        println("granted")
    }
}
