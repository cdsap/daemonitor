package com.gradlewatcher.collect

import com.gradlewatcher.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleProcessClassifierTest {

    @Test
    fun `classifies gradle daemon`() {
        val cl = "java -cp gradle-launcher.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3"
        assertEquals(ProcessType.GRADLE_DAEMON, GradleProcessClassifier.classify(cl))
    }

    @Test
    fun `classifies gradle wrapper`() {
        assertEquals(
            ProcessType.GRADLE_WRAPPER,
            GradleProcessClassifier.classify("/bin/sh /Users/dev/proj/gradlew build"),
        )
    }

    @Test
    fun `classifies kotlin daemon`() {
        val cl = "java -cp kotlin-daemon.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon"
        assertEquals(ProcessType.KOTLIN_DAEMON, GradleProcessClassifier.classify(cl))
    }

    @Test
    fun `classifies test worker`() {
        val cl = "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'"
        assertEquals(ProcessType.TEST_WORKER, GradleProcessClassifier.classify(cl))
    }

    @Test
    fun `classifies generic java-with-gradle as java-gradle-related`() {
        val cl = "java -cp /Users/dev/.gradle/caches/foo.jar com.example.Tool"
        assertEquals(ProcessType.JAVA_GRADLE_RELATED, GradleProcessClassifier.classify(cl))
    }

    @Test
    fun `returns null for unrelated process`() {
        assertNull(GradleProcessClassifier.classify("/Applications/Safari.app/Contents/MacOS/Safari"))
        assertNull(GradleProcessClassifier.classify("node server.js"))
    }
}
