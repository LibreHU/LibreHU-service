// Hardware-independent logic (MCU protocol, audio chip drivers): plain Kotlin/JVM, unit tested off-device.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

// librehu-car-tool.jar: the car data engine on a computer (check profiles, decode a CAN export), used by the
// LibreHU-car-profiles repository; `java -jar librehu-car-tool.jar check brands/`.
tasks.register<Jar>("carToolJar") {
    archiveBaseName.set("librehu-car-tool")
    archiveVersion.set("")
    manifest { attributes["Main-Class"] = "org.librehu.core.can.CarTool" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
