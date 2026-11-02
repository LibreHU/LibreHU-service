import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
}

// Same versions as the service: versionName = `git describe`, versionCode = number of commits.
fun git(vararg args: String): String? =
    try {
        val out = ByteArrayOutputStream()
        val p = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.copyTo(out)
        if (p.waitFor() == 0) out.toString().trim() else null
    } catch (_: Exception) {
        null
    }

fun branchName(): String = System.getenv("GITHUB_REF_NAME")?.takeIf { it.isNotBlank() } ?: git("rev-parse", "--abbrev-ref", "HEAD") ?: "local"

android {
    namespace = "org.librehu.jacsettings"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.librehu.jacsettings"
        minSdk = 28
        targetSdk = 35
        versionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = (git("describe", "--tags", "--always", "--dirty", "--exclude", "latest-*", "--exclude", "build-*") ?: "dev") + "-" + branchName()
    }

    // The LibreHU key, as the other LibreHU apps (LIBREHU_KEYSTORE* from the CI secrets, else keystore/librehu.jks).
    signingConfigs {
        create("librehu") {
            storeFile = System.getenv("LIBREHU_KEYSTORE")?.let { file(it) } ?: rootProject.file("keystore/librehu.jks")
            storePassword = System.getenv("LIBREHU_KEYSTORE_PASSWORD") ?: "librehu"
            keyAlias = System.getenv("LIBREHU_KEY_ALIAS") ?: "librehu"
            keyPassword = System.getenv("LIBREHU_KEY_PASSWORD") ?: "librehu"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("librehu") }
        // R8: AGP 9 adds the Kotlin standard library to every module (2.3 MB of dex for a Java provider); the
        // provider itself is kept through the manifest.
        getByName("release") {
            signingConfig = signingConfigs.getByName("librehu")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
