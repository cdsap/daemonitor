plugins {
    kotlin("jvm") version "2.4.20"
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.cdsap.daemonitor"
// Independent artifact line from product/cored (1.2.x). Bump this when cutting a
// product release that ships plugin changes; tag release publishes to Plugin Portal.
version = "0.1.0"

publishing {
    publications {
        // java-gradle-plugin registers pluginMaven late; configure artifactId when present.
        withType<MavenPublication>().configureEach {
            if (name == "pluginMaven") {
                artifactId = "daemonitor-gradle-plugin"
            }
        }
    }
}

gradlePlugin {
    website = "https://github.com/cdsap/daemonitor"
    vcsUrl = "https://github.com/cdsap/daemonitor.git"
    plugins {
        create("daemonitor") {
            id = "io.github.cdsap.daemonitor"
            implementationClass = "io.github.cdsap.daemonitor.gradle.DaemonitorGradlePlugin"
            displayName = "Daemonitor"
            description =
                "Build-observation sessions against daemonitor-cored with local report and optional GBOS / Develocity projection"
            tags = listOf("daemonitor", "gradle", "observability", "develocity", "gbos")
        }
    }
}

dependencies {
    // Optional at consumer runtime: the bridge is reflection-only and is gated by Class.forName.
    compileOnly("com.gradle:develocity-gradle-plugin:4.6.1")
    implementation("io.github.cdsap:build-observability-core:0.0.7")
    testImplementation(kotlin("test"))
    testImplementation("com.gradle:develocity-gradle-plugin:4.6.1")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

tasks.register("printGradlePluginVersion") {
    description = "Prints the Gradle plugin version for release/CI checks"
    doLast { println(version) }
}
