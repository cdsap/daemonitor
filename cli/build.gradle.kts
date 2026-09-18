plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "io.github.cdsap.daemonitor"
version = rootProject.version

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

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
    dependsOn(tasks.named("installDist"))
    systemProperty(
        "daemonitor.cli.home",
        layout.buildDirectory.dir("install/daemonitor-cli").get().asFile.absolutePath,
    )
}
