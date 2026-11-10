plugins {
    kotlin("jvm") version "2.4.20"
    `java-gradle-plugin`
}

group = "io.github.cdsap.daemonitor"
version = "0.1.0"

gradlePlugin {
    plugins {
        create("daemonitor") {
            id = "io.github.cdsap.daemonitor"
            implementationClass = "io.github.cdsap.daemonitor.gradle.DaemonitorGradlePlugin"
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
