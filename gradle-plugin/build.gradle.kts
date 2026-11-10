plugins {
    kotlin("jvm") version "2.4.20"
    `java-gradle-plugin`
}

group = "io.github.cdsap.daemonitor"
version = "0.1.0"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

gradlePlugin {
    plugins {
        create("daemonitor") {
            id = "io.github.cdsap.daemonitor"
            implementationClass = "io.github.cdsap.daemonitor.gradle.DaemonitorGradlePlugin"
        }
    }
}

dependencies {
    // Optional at consumer runtime: the bridge is reflection-only and is gated by Class.forName.
    compileOnly("com.gradle:develocity-gradle-plugin:4.6.1")
    implementation("io.github.cdsap:build-observability-core:0.0.7")
    testImplementation(kotlin("test"))
    testImplementation("com.gradle:develocity-gradle-plugin:4.6.1")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
