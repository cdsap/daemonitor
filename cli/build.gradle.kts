import org.gradle.jvm.application.tasks.CreateStartScripts

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

@Suppress("UNCHECKED_CAST")
val stageDaemonitorCoredForCli =
    rootProject.tasks.named("stageDaemonitorCoredForCli")
val stageNativeDaemonitorCli =
    rootProject.tasks.named("stageNativeDaemonitorCli")
val coredCliDistDir =
    rootProject.layout.buildDirectory.dir("cored-cli-dist")
val nativeCliDistDir =
    rootProject.layout.buildDirectory.dir("native-cli-dist")

// The default installed CLI is the native Go Bubble Tea client. Keep the
// Kotlin application source available for :cli:run and tests, but do not let
// its generated JVM launchers become the installed CLI when Go is available.
tasks.withType<CreateStartScripts>().configureEach {
    dependsOn(stageNativeDaemonitorCli)
    doLast {
        val nativeCli = nativeCliDistDir.get().file("bin/daemonitor-cli").asFile
        val nativeCliExe = nativeCliDistDir.get().file("bin/daemonitor-cli.exe").asFile
        if (nativeCli.isFile || nativeCliExe.isFile) {
            outputDir?.let { generatedDir ->
                delete(generatedDir.resolve("daemonitor-cli"))
                delete(generatedDir.resolve("daemonitor-cli.bat"))
            }
        }
    }
}

distributions {
    main {
        contents {
            from(coredCliDistDir) {
                // bin/daemonitor-cored — empty when Go was unavailable / stage skipped
            }
            from(nativeCliDistDir) {
                // The cored binary is staged above; this source replaces the
                // generated JVM launcher with the native Bubble Tea launcher.
                exclude("bin/daemonitor-cored", "bin/daemonitor-cored.exe")
            }
        }
    }
}

listOf("installDist", "distZip", "distTar").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(stageDaemonitorCoredForCli, stageNativeDaemonitorCli)
    }
}
