plugins {
    kotlin("jvm") version "2.0.21"
    id("app.cash.sqldelight") version "2.0.2"
}

group = "io.github.cdsap.daemonitor"
version = rootProject.version

val buildInfoDirectory = layout.buildDirectory.dir("generated/build-info")
val buildCommit = providers.environmentVariable("GITHUB_SHA")
    .map { it.take(8) }
    .orElse(providers.provider {
        runCatching {
            val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                .directory(rootDir.parentFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() == 0) output else "unknown"
        }.getOrDefault("unknown")
    })
val generateBuildInfo = tasks.register("generateBuildInfo") {
    val appVersion = project.version.toString()
    val distribution = (rootProject.findProperty("daemonitor.distribution") as String?)
        ?.trim()
        ?.uppercase()
        ?.takeIf { it == "APP_STORE" || it == "APPSTORE" || it == "MAC_APP_STORE" }
        ?: "DIRECT"
    inputs.property("version", appVersion)
    inputs.property("commit", buildCommit)
    inputs.property("distribution", distribution)
    outputs.dir(buildInfoDirectory)

    doLast {
        buildInfoDirectory.get().file("daemonitor-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText(
                "version=$appVersion\n" +
                    "commit=${buildCommit.get()}\n" +
                    "distribution=$distribution\n",
            )
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
    api("com.github.oshi:oshi-core:6.6.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

sqldelight {
    databases {
        create("WatcherDb") {
            packageName.set("io.github.cdsap.daemonitor.store.db")
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
