import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Version matrix (U1 / KTD): Kotlin 2.4.20 ↔ Compose 1.7.3 ↔ SQLDelight 2.0.2 ↔ OSHI 6.x.
// The Compose Gradle plugin and SQLDelight plugin both pin a Kotlin range; this triple is
// mutually compatible. Bump as a set, not individually.
plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "io.github.cdsap.daemonitor"
version = "1.1.0"

val nativePackageVersion = "1.1.0"

val distributionChannel = (findProperty("daemonitor.distribution") as String?)
    ?.trim()
    ?.uppercase()
    .orEmpty()
    .ifEmpty { "DIRECT" }
    .let { raw ->
        when (raw) {
            "APP_STORE", "APPSTORE", "MAC_APP_STORE" -> "APP_STORE"
            else -> "DIRECT"
        }
    }
val isAppStoreDistribution = distributionChannel == "APP_STORE"

dependencies {
    implementation(project(":core"))
    implementation(project(":cli"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Compose UI-layer tests (mount screens, query/click nodes) — runs on the CI OS matrix.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform { excludeTags("documentation") }
}

tasks.register<Test>("captureReadmeScreenshots") {
    group = "documentation"
    description = "Renders privacy-safe README screenshots from deterministic sample UI state"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("documentation") }
    filter { includeTestsMatching("io.github.cdsap.daemonitor.docs.ReadmeScreenshotCapture") }
    systemProperty("user.timezone", "UTC")
}

tasks.register("printNativePackageVersion") {
    description = "Prints the native installer version for CI artifact naming"
    doLast { println(nativePackageVersion) }
}

tasks.register<JavaExec>("runHeadless") {
    group = "application"
    description = "Runs Daemonitor without the desktop UI"
    mainClass.set("io.github.cdsap.daemonitor.DaemonitorHeadless")
    classpath = sourceSets.main.get().runtimeClasspath
}

// --- daemonitor-cored (Go) host build; consumed by CLI dist + Compose app resources ---

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

val buildDaemonitorCored = tasks.register<Exec>("buildDaemonitorCored") {
    group = "distribution"
    description = "Build daemonitor-cored (Go) for the host OS when the go toolchain is available."
    onlyIf { goAvailable.getOrElse(false) }
    workingDir = file("cored")
    outputs.file(coredOutput)
    inputs.files(
        fileTree(file("cored")) {
            include("**/*.go", "go.mod", "go.sum")
            exclude("bin/**", "**/.smoke-tmp/**")
        },
    )
    commandLine(
        "go", "build",
        "-trimpath",
        "-ldflags=-s -w",
        "-o", coredOutput.get().asFile.absolutePath,
        "./cmd/daemonitor-cored",
    )
    environment("CGO_ENABLED", "0")
}

val goCliBinaryName =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "daemonitor-cli.exe"
    } else {
        "daemonitor-cli"
    }
val goCliOutput = layout.buildDirectory.file("cored/$goCliBinaryName")

val buildDaemonitorGoCli = tasks.register<Exec>("buildDaemonitorGoCli") {
    group = "distribution"
    description = "Build native Go daemonitor-cli (Bubble Tea TUI) for the host OS."
    onlyIf { goAvailable.getOrElse(false) }
    workingDir = file("cored")
    outputs.file(goCliOutput)
    inputs.files(
        fileTree(file("cored")) {
            include("**/*.go", "go.mod", "go.sum")
            exclude("bin/**", "**/.smoke-tmp/**")
        },
    )
    commandLine(
        "go", "build",
        "-trimpath",
        "-ldflags=-s -w",
        "-o", goCliOutput.get().asFile.absolutePath,
        "./cmd/daemonitor-cli",
    )
    environment("CGO_ENABLED", "0")
}

val nativeCliDistDir = layout.buildDirectory.dir("native-cli-dist")
val stageNativeDaemonitorCli = tasks.register<Sync>("stageNativeDaemonitorCli") {
    group = "distribution"
    description = "Stage native Go daemonitor-cli + daemonitor-cored for archive packaging."
    dependsOn(buildDaemonitorCored, buildDaemonitorGoCli)
    onlyIf {
        coredOutput.get().asFile.exists() && goCliOutput.get().asFile.exists()
    }
    into(nativeCliDistDir.map { it.dir("bin") })
    from(coredOutput) { rename { coredBinaryName } }
    from(goCliOutput) { rename { goCliBinaryName } }
    doLast {
        listOf(coredBinaryName, goCliBinaryName).forEach { name ->
            val staged = nativeCliDistDir.get().asFile.resolve("bin/$name")
            if (staged.isFile && !name.endsWith(".exe")) {
                staged.setExecutable(true, false)
            }
        }
    }
}

val crossCompileDaemonitorCored = tasks.register<Exec>("crossCompileDaemonitorCored") {
    group = "distribution"
    description = "Cross-compile daemonitor-cored for darwin/linux/windows amd64+arm64 (CGO_ENABLED=0)."
    onlyIf { goAvailable.getOrElse(false) }
    workingDir = file("cored")
    val outDir = layout.buildDirectory.dir("cored-cross")
    outputs.dir(outDir)
    inputs.files(
        fileTree(file("cored")) {
            include("**/*.go", "go.mod", "go.sum", "scripts/cross-compile-cored.sh")
            exclude("bin/**", "**/.smoke-tmp/**")
        },
    )
    commandLine("bash", "scripts/cross-compile-cored.sh", outDir.get().asFile.absolutePath)
}

val coredCliDistDir = layout.buildDirectory.dir("cored-cli-dist")
val stageDaemonitorCoredForCli = tasks.register<Sync>("stageDaemonitorCoredForCli") {
    group = "distribution"
    dependsOn(buildDaemonitorCored)
    onlyIf { coredOutput.get().asFile.exists() }
    from(coredOutput)
    into(coredCliDistDir.map { it.dir("bin") })
    rename { coredBinaryName }
    doLast {
        val staged = coredCliDistDir.get().asFile.resolve("bin/$coredBinaryName")
        if (staged.isFile && !coredBinaryName.endsWith(".exe")) {
            staged.setExecutable(true, false)
        }
    }
}

/**
 * Stage for Compose Desktop appResources (`common/` → all packages).
 * Runtime path: `compose.application.resources.dir` / daemonitor-cored
 * (typically `<app>/app/resources/daemonitor-cored`).
 */
val coredAppResourcesRoot = layout.buildDirectory.dir("cored-app-resources")
val stageDaemonitorCoredAppResources = tasks.register<Sync>("stageDaemonitorCoredAppResources") {
    group = "distribution"
    dependsOn(buildDaemonitorCored)
    onlyIf { coredOutput.get().asFile.exists() }
    from(coredOutput)
    into(coredAppResourcesRoot.map { it.dir("common") })
    rename { coredBinaryName }
    doLast {
        val staged = coredAppResourcesRoot.get().asFile.resolve("common/$coredBinaryName")
        if (staged.isFile && !coredBinaryName.endsWith(".exe")) {
            staged.setExecutable(true, false)
        }
    }
}

fun markPackagedCoredExecutable() {
    val appRoot = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
    if (!appRoot.isDirectory) return
    appRoot.walkTopDown()
        .filter { it.isFile && (it.name == "daemonitor-cored" || it.name == "daemonitor-cored.exe") }
        .forEach { it.setExecutable(true) }
}

compose.desktop {
    application {
        mainClass = "io.github.cdsap.daemonitor.Daemonitor"
        nativeDistributions {
            // One format per OS; each is only buildable on its own platform (jpackage limitation).
            // APP_STORE adds macOS .pkg for App Store / TestFlight packaging experiments.
            if (isAppStoreDistribution) {
                targetFormats(TargetFormat.Pkg, TargetFormat.Msi, TargetFormat.Deb)
            } else {
                targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            }
            packageName = "Daemonitor"
            packageVersion = nativePackageVersion
            modules(
                "java.sql",
                "java.net.http",
                "jdk.httpserver",
                // Live JVM heap via Attach + local JMX (issue #159).
                "jdk.attach",
                "java.management",
                "jdk.management.agent",
            )
            appResourcesRootDir.set(coredAppResourcesRoot)

            // Per-platform installer/app icons (jpackage requires the native format per OS).
            macOS {
                iconFile.set(project.file("icons/daemonitor.icns"))
                bundleID = "io.github.cdsap.daemonitor"
                if (isAppStoreDistribution) {
                    appStore = true
                    appCategory = "public.app-category.developer-tools"
                    entitlementsFile.set(project.file("packaging/macos/app-store.entitlements"))
                    runtimeEntitlementsFile.set(project.file("packaging/macos/app-store-runtime.entitlements"))
                }
            }
            windows { iconFile.set(project.file("icons/daemonitor.ico")) }
            linux { iconFile.set(project.file("icons/daemonitor.png")) }
        }
    }
}

listOf("prepareAppResources", "createDistributable", "packageDeb", "packageMsi", "packageDmg", "packagePkg").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(stageDaemonitorCoredAppResources)
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast { markPackagedCoredExecutable() }
}
tasks.matching { it.name.startsWith("package") }.configureEach {
    doLast { markPackagedCoredExecutable() }
}
