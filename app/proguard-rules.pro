# LibreHU: R8 shrinks and optimises the code (Jetpack Compose is much faster that way, developer.android.com
# "Compose performance"), without renaming it: logcat stack traces stay readable, and the classes named in strings
# (root helpers run by app_process, JNI, AIDL parcelables shared with the other LibreHU apps) keep their names.
-dontobfuscate

# Every native method, even one Java never calls: RegisterNatives fails on a missing one.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Run as root by AtcTools through app_process (only its name is referenced).
-keep class org.librehu.service.atc.AtcHelper {
    public static void main(java.lang.String[]);
}
