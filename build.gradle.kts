buildscript {
    // The Android and Kotlin plugins must share the root class loader. AGP is left out with `-PcoreOnly`, so the
    // pure Kotlin core builds and tests without the Google Maven repository.
    if (!providers.gradleProperty("coreOnly").isPresent) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:9.4.0")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
