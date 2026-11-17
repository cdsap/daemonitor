plugins {
    kotlin("jvm") version "2.4.20"
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.cdsap.daemonitor"
// Independent artifact line from product/cored (1.3.x). Bump this when cutting a
// product release that ships plugin changes; tag release publishes to Plugin Portal.
version = "0.1.1"

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
    // Portal reviewers: why this plugin exists is documented on the site and in gradle-plugin/README.md.
    website = "https://cdsap.github.io/daemonitor/#gradle-plugin"
    vcsUrl = "https://github.com/cdsap/daemonitor.git"
    plugins {
        create("daemonitor") {
            id = "io.github.cdsap.daemonitor"
            implementationClass = "io.github.cdsap.daemonitor.gradle.DaemonitorGradlePlugin"
            displayName = "Daemonitor"
            description =
                "Marks each Gradle build as a daemonitor-cored observation window so CI/local runs get per-build process RSS/heap/GC (local report + optional GBOS/Develocity)—not only live desktop/CLI monitoring"
            // Portal forbids the tags "gradle" and "plugin".
            tags = listOf("daemonitor", "observability", "gbos", "build")
        }
    }
}

dependencies {
    // Optional at consumer runtime: the bridge is reflection-only and is gated by Class.forName.
    compileOnly("com.gradle:develocity-gradle-plugin:4.6.1")
    implementation("io.github.cdsap:build-observability-core:0.0.7")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation("com.gradle:develocity-gradle-plugin:4.6.1")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

tasks.register("printGradlePluginVersion") {
    description = "Prints the Gradle plugin version for release/CI checks"
    doLast { println(version) }
}
