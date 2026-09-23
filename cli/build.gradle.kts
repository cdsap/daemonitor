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

val goAvailable: Provider<Boolean> = providers.exec {
    commandLine("go", "version")
    isIgnoreExitValue = true
}.result.map { it.exitValue == 0 }

val coredBinaryName =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "daemonitor-cored.exe"
    } else {
        "daemonitor-cored"
    }

val coredOutput = layout.buildDirectory.file("cored/$coredBinaryName")
val coredDistBin = layout.buildDirectory.dir("cored-dist/bin")

val buildDaemonitorCored = tasks.register<Exec>("buildDaemonitorCored") {
    group = "distribution"
    description = "Build daemonitor-cored (Go) for the host OS when the go toolchain is available."
    onlyIf { goAvailable.getOrElse(false) }
    workingDir = rootProject.file("spikes/go-core")
    outputs.file(coredOutput)
    inputs.files(
        fileTree(rootProject.file("spikes/go-core")) {
            include("**/*.go", "go.mod", "go.sum")
            exclude("bin/**", "**/.smoke-tmp/**")
        },
    )
    commandLine(
        "go", "build",
        "-o", coredOutput.get().asFile.absolutePath,
        "./cmd/daemonitor-cored",
    )
}

/** Stage cored into a layout the application distribution CopySpec can consume. */
val stageDaemonitorCored = tasks.register<Sync>("stageDaemonitorCored") {
    dependsOn(buildDaemonitorCored)
    onlyIf { coredOutput.get().asFile.exists() }
    from(coredOutput)
    into(coredDistBin)
    rename { coredBinaryName }
}

// Include staged cored in installDist / distZip / distTar (not only installDist doLast).
distributions {
    main {
        contents {
            from(layout.buildDirectory.dir("cored-dist")) {
                // bin/daemonitor-cored — empty when Go was unavailable / stage skipped
            }
        }
    }
}

listOf("installDist", "distZip", "distTar").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(stageDaemonitorCored)
    }
}
