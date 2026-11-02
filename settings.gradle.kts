pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android (ELM327 USB adapters).
        maven("https://jitpack.io")
    }
}

rootProject.name = "LibreHU-service"
include(":core")
// The Android module needs the Android SDK; `-PcoreOnly` builds and tests the pure Kotlin core without it.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
    // Replacement for the settings provider of Jancar's settings app (com.jancar.settings), a small APK of its own.
    include(":jacsettings")
}
