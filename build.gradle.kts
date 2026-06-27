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
version = "0.1.0"

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
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "io.github.cdsap.daemonitor.Daemonitor"
        nativeDistributions {
            // One format per OS; each is only buildable on its own platform (jpackage limitation).
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Daemonitor"
            packageVersion = "1.0.0"

            // Per-platform installer/app icons (jpackage requires the native format per OS).
            macOS { iconFile.set(project.file("icons/daemonitor.icns")) }
            windows { iconFile.set(project.file("icons/daemonitor.ico")) }
            linux { iconFile.set(project.file("icons/daemonitor.png")) }
        }
    }
}
