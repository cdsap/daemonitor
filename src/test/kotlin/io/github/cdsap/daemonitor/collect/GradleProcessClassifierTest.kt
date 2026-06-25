package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.ProcessType
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
    fun `classifies the jar-launched wrapper JVM as wrapper, not Java (Gradle)`() {
        // The real form observed on macOS: launched via `-jar gradle-wrapper.jar` with
        // appname=gradlew, so neither GradleWrapperMain nor a bare `gradlew` token is present.
        // It carries `-Dorg.gradle.*`, so without explicit wrapper signals it leaks into the
        // JAVA_GRADLE_RELATED fallback.
        val cl = "java -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew " +
            "-jar /Users/dev/proj/gradle/wrapper/gradle-wrapper.jar build"
        assertEquals(ProcessType.GRADLE_WRAPPER, GradleProcessClassifier.classify(cl))
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
    fun `classifies a java process with a real Gradle runtime marker`() {
        assertEquals(
            ProcessType.JAVA_GRADLE_RELATED,
            GradleProcessClassifier.classify("java -Dorg.gradle.internal.worker=1 -cp /x/foo.jar com.example.Tool"),
        )
        assertEquals(
            ProcessType.JAVA_GRADLE_RELATED,
            GradleProcessClassifier.classify("java -cp /x/gradle-tooling-api.jar org.gradle.tooling.internal.Foo"),
        )
    }

    @Test
    fun `does NOT classify a Gradle-built app whose only gradle is the dependency cache path`() {
        // The over-detection bug: any JVM whose deps resolve from ~/.gradle/caches contains
        // "gradle" in its classpath — but that is not a Gradle process (e.g. this watcher itself).
        val watcherLike = "java -Dcompose.application=true -cp /Users/dev/.gradle/caches/oshi-core.jar io.github.cdsap.daemonitor.MainKt"
        assertNull(GradleProcessClassifier.classify(watcherLike))
    }

    @Test
    fun `returns null for unrelated process`() {
        assertNull(GradleProcessClassifier.classify("/Applications/Safari.app/Contents/MacOS/Safari"))
        assertNull(GradleProcessClassifier.classify("node server.js"))
    }
}
