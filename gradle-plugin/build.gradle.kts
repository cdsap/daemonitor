plugins {
    kotlin("jvm") version "2.4.20"
}

group = "io.github.cdsap.daemonitor"
version = "0.1.0"

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
