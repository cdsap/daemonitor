import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Version matrix (U1 / KTD): Kotlin 2.0.21 ↔ Compose 1.7.3 ↔ SQLDelight 2.0.2 ↔ OSHI 6.x.
// The Compose Gradle plugin and SQLDelight plugin both pin a Kotlin range; this triple is
// mutually compatible. Bump as a set, not individually.
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("app.cash.sqldelight") version "2.0.2"
}

group = "io.github.cdsap.daemonitor"
version = "0.1.1"

val nativePackageVersion = "1.0.1"

val buildInfoDirectory = layout.buildDirectory.dir("generated/build-info")
val buildCommit = providers.environmentVariable("GITHUB_SHA")
    .map { it.take(8) }
    .orElse(providers.provider {
        runCatching {
            val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0) output else "unknown"
        }.getOrDefault("unknown")
    })
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    inputs.property("commit", buildCommit)
    outputs.dir(buildInfoDirectory)

    doLast {
        buildInfoDirectory.get().file("daemonitor-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=$appVersion\ncommit=${buildCommit.get()}\n")
        }
    }
}

sourceSets.main {
    resources.srcDir(buildInfoDirectory)
}

tasks.processResources {
    dependsOn(generateBuildInfo)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Process introspection
    implementation("com.github.oshi:oshi-core:6.6.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Persistence (SQLDelight JVM)
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Compose UI-layer tests (mount screens, query/click nodes) — runs on the CI OS matrix.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

sqldelight {
    databases {
        create("WatcherDb") {
            packageName.set("io.github.cdsap.daemonitor.store.db")
            // Explicit dialect dependency — the common SQLDelight-on-JVM setup pitfall.
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:2.0.2")
        }
    }
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

compose.desktop {
    application {
        mainClass = "io.github.cdsap.daemonitor.Daemonitor"
        nativeDistributions {
            // One format per OS; each is only buildable on its own platform (jpackage limitation).
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Daemonitor"
            packageVersion = nativePackageVersion
            modules("java.sql")

            // Per-platform installer/app icons (jpackage requires the native format per OS).
            macOS { iconFile.set(project.file("icons/daemonitor.icns")) }
            windows { iconFile.set(project.file("icons/daemonitor.ico")) }
            linux { iconFile.set(project.file("icons/daemonitor.png")) }
        }
    }
}
