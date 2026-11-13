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
    fun `classifies gradle test worker by process name`() {
        val cl = "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'"
        assertEquals(ProcessType.TEST_WORKER, GradleProcessClassifier.classify(cl))
    }

    @Test
    fun `classifies generic gradle worker by process name`() {
        val cl = "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Worker Daemon 11'"
        assertEquals(ProcessType.GRADLE_WORKER, GradleProcessClassifier.classify(cl))
    }

    @Test
    fun `falls back to unknown gradle worker for an unrecognized process name`() {
        val cl = "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Custom Worker 1'"
        assertEquals(ProcessType.UNKNOWN_GRADLE_WORKER, GradleProcessClassifier.classify(cl))
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

    @Test
    fun `classifies bazel server from A-server jar and product_name`() {
        val cl = "java -jar /x/install/A-server.jar --workspace_directory=/tmp/ws --product_name=Bazel"
        assertEquals(ProcessType.BAZEL_SERVER, GradleProcessClassifier.classify(cl))
        assertEquals("/tmp/ws", GradleProcessClassifier.bazelWorkspaceDirectory(cl))
    }

    @Test
    fun `rejects Bazelisk product_name as bazel server`() {
        assertNull(
            GradleProcessClassifier.classify(
                "java -jar /x/A-server.jar --product_name=Bazelisk --workspace_directory=/tmp/ws",
            ),
        )
    }

    @Test
    fun `classifies bazel test and worker markers`() {
        assertEquals(
            ProcessType.BAZEL_TEST,
            GradleProcessClassifier.classify(
                "java -Dbazel.test_suite=demo.SlowTest com.google.testing.junit.runner.BazelTestRunner",
            ),
        )
        assertEquals(
            ProcessType.BAZEL_TEST,
            GradleProcessClassifier.classify("bash external/bazel_tools/tools/test/test-setup.sh ./SlowTest"),
        )
        assertEquals(
            ProcessType.BAZEL_WORKER,
            GradleProcessClassifier.classify(
                "java -jar /x/_bazel_dev/JavaBuilder_deploy.jar --persistent_worker",
            ),
        )
        assertEquals(
            ProcessType.BAZEL_WORKER,
            GradleProcessClassifier.classify(
                "/path/external/rules_swift+/tools/worker/worker swiftc --persistent_worker",
            ),
        )
        assertNull(GradleProcessClassifier.classify("java -jar /opt/other/tool.jar --persistent_worker"))
    }

    @Test
    fun `native bazel test leaf requires BAZEL_TEST parent`() {
        val native = "/Users/dev/Library/Caches/bazel/_bazel_dev/hash/sandbox/darwin-sandbox/11/execroot/_main/" +
            "bazel-out/darwin_arm64-fastbuild/bin/slow_test_/slow_test.runfiles/_main/slow_test_/slow_test"
        assertNull(GradleProcessClassifier.classify(native))
        assertEquals(
            ProcessType.BAZEL_TEST,
            GradleProcessClassifier.promoteBazelNativeTest(native, ProcessType.BAZEL_TEST),
        )
        assertNull(GradleProcessClassifier.promoteBazelNativeTest(native, ProcessType.BAZEL_SERVER))
        assertNull(GradleProcessClassifier.promoteBazelNativeTest(native, null))
    }
}
