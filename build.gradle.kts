import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Version matrix (U1 / KTD): Kotlin 2.0.21 ↔ Compose 1.7.3 ↔ SQLDelight 2.0.2 ↔ OSHI 6.x.
// The Compose Gradle plugin and SQLDelight plugin both pin a Kotlin range; this triple is
// mutually compatible. Bump as a set, not individually.
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "io.github.cdsap.daemonitor"
version = "1.0.7"

val nativePackageVersion = "1.0.7"

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
            modules("java.sql", "java.net.http", "jdk.httpserver")

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
