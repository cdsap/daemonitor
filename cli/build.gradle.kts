plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "io.github.cdsap.daemonitor"
version = rootProject.version

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.cdsap.daemonitor.DaemonitorCli")
    applicationName = "daemonitor-cli"
}

tasks.test {
    useJUnitPlatform()
}
