plugins {
    // The Android plugin is applied in :app only, so `-PcoreOnly` works without the Google Maven repository.
    alias(libs.plugins.kotlin.jvm) apply false
}
