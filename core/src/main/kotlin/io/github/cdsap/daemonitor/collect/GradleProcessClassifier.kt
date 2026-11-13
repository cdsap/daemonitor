package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.model.ProcessType

/**
 * Classifies a process command line into a [ProcessType], or `null` when it is not Gradle-related
 * (U2). Order matters: more specific daemon/worker markers are checked before the generic
 * java-with-gradle fallback.
 */
object GradleProcessClassifier {

    fun classify(commandLine: String): ProcessType? {
        val cl = commandLine
        return when {
            cl.contains("org.gradle.launcher.daemon.bootstrap.GradleDaemon") ||
                cl.contains("GradleDaemon") -> ProcessType.GRADLE_DAEMON

            cl.contains("org.jetbrains.kotlin.daemon") ||
                cl.contains("KotlinCompileDaemon") -> ProcessType.KOTLIN_DAEMON

            isBazelServer(cl) -> ProcessType.BAZEL_SERVER

            isBazelTest(cl) -> ProcessType.BAZEL_TEST

            isBazelWorker(cl) -> ProcessType.BAZEL_WORKER

            isGradleWorker(cl) -> classifyGradleWorker(cl)

            // The wrapper JVM is usually launched as `java -jar …/gradle/wrapper/gradle-wrapper.jar`
            // with `-Dorg.gradle.appname=gradlew` — so neither the GradleWrapperMain class nor a
            // bare `gradlew` token appears on the command line. Match the jar and the appname
            // property too, otherwise the wrapper falls through to the JAVA_GRADLE_RELATED fallback
            // (it carries `-Dorg.gradle.*`) and is mislabeled "Java (Gradle)".
            cl.contains("org.gradle.wrapper.GradleWrapperMain") ||
                cl.contains("gradle-wrapper.jar") ||
                cl.contains("org.gradle.appname=gradlew") ||
                containsGradlewInvocation(cl) -> ProcessType.GRADLE_WRAPPER

            isJava(cl) && hasGradleRuntimeMarker(cl) -> ProcessType.JAVA_GRADLE_RELATED

            else -> null
        }
    }

    private fun containsGradlewInvocation(cl: String): Boolean =
        Regex("""(^|[\s/])gradlew(\s|$)""").containsMatchIn(cl)

    /** Bazel 9.x server: `-jar …/A-server.jar` plus `--product_name=Bazel` or `--output_base=`. */
    private fun isBazelServer(cl: String): Boolean =
        BAZEL_SERVER_JAR.containsMatchIn(cl) &&
            (
                BAZEL_PRODUCT_NAME.containsMatchIn(cl) ||
                    cl.contains("--output_base=")
                )

    /**
     * Hard argv Bazel test markers (Java/Kotlin/Go/iOS probes): JVM `BazelTestRunner` /
     * `-Dbazel.test_suite=`, or shared `tools/test/test-setup.sh` spine (also on
     * `process-wrapper` argv). Native sandboxed runfiles leaves need
     * [promoteBazelNativeTest] with a `BAZEL_TEST` parent.
     */
    private fun isBazelTest(cl: String): Boolean =
        cl.contains("com.google.testing.junit.runner.BazelTestRunner") ||
            cl.contains("-Dbazel.test_suite=") ||
            BAZEL_TEST_SETUP.containsMatchIn(cl)

    /**
     * Argv half of a native Bazel test leaf (sandbox + `.runfiles`, not a persistent
     * worker). Pair with a [ProcessType.BAZEL_TEST] parent via [promoteBazelNativeTest].
     */
    fun isBazelNativeTestCandidate(commandLine: String): Boolean {
        if (BAZEL_PERSISTENT_WORKER.containsMatchIn(commandLine)) return false
        return BAZEL_SANDBOX.containsMatchIn(commandLine) &&
            BAZEL_RUNFILES.containsMatchIn(commandLine)
    }

    /** Promote a native sandboxed runfiles binary when its parent is already `BAZEL_TEST`. */
    fun promoteBazelNativeTest(commandLine: String, parentType: ProcessType?): ProcessType? =
        if (parentType == ProcessType.BAZEL_TEST && isBazelNativeTestCandidate(commandLine)) {
            ProcessType.BAZEL_TEST
        } else {
            null
        }

    /** Bazel persistent workers (JavaBuilder / Kotlin / Swift), distinct from [ProcessType.BAZEL_TEST]. */
    private fun isBazelWorker(cl: String): Boolean =
        BAZEL_PERSISTENT_WORKER.containsMatchIn(cl) &&
            (
                cl.contains("JavaBuilder_deploy.jar") ||
                    cl.contains("io.bazel.") ||
                    cl.contains("rules_kotlin") ||
                    cl.contains("rules_swift") ||
                    cl.contains("_bazel_") ||
                    cl.contains("external/rules_java")
                )

    fun bazelWorkspaceDirectory(commandLine: String): String? {
        val match = BAZEL_WORKSPACE_DIRECTORY.find(commandLine) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private val BAZEL_SERVER_JAR = Regex("""(^|[\s/\\])A-server\.jar["']?(\s|$)""")
    private val BAZEL_PRODUCT_NAME = Regex("""--product_name=Bazel(\s|$)""")
    private val BAZEL_TEST_SETUP = Regex("""(^|[\s/\\])tools/test/test-setup\.sh(\s|$)""")
    private val BAZEL_PERSISTENT_WORKER = Regex("""--persistent_worker(\s|$)""")
    private val BAZEL_SANDBOX = Regex(
        """(^|[\s/\\])(?:darwin|linux|processwrapper)-sandbox(/|\\)|(^|[\s/\\])sandbox(/|\\)""",
    )
    private val BAZEL_RUNFILES = Regex("""\.runfiles(/|\\)""")
    private val BAZEL_WORKSPACE_DIRECTORY =
        Regex("""--workspace_directory=(?:"([^"]+)"|'([^']+)'|([^\s]+))""")

    private fun isGradleWorker(cl: String): Boolean =
        cl.contains("worker.org.gradle.process.internal.worker.GradleWorkerMain") ||
            cl.contains("org.gradle.process.internal.worker.GradleWorkerMain")

    private fun classifyGradleWorker(cl: String): ProcessType = when {
        cl.contains("Gradle Test Executor") -> ProcessType.TEST_WORKER
        cl.contains("Gradle Worker Daemon") -> ProcessType.GRADLE_WORKER
        else -> ProcessType.UNKNOWN_GRADLE_WORKER
    }

    private fun isJava(cl: String): Boolean =
        Regex("""(^|[\s/])java(\s|$)""").containsMatchIn(cl)

    // A genuine Gradle *runtime* signal — a Gradle system property, a Gradle launcher/worker/tooling
    // class, or the worker main. Deliberately does NOT match a bare "~/.gradle/caches/…" classpath
    // path: every JVM whose dependencies were fetched by Gradle has that, which over-detected any
    // Gradle-built app (including this watcher itself).
    private val GRADLE_RUNTIME_MARKER = Regex(
        """(-Dorg\.gradle\.|org\.gradle\.(launcher|process|tooling|internal|api|workers)\.|GradleWorkerMain)""",
    )

    private fun hasGradleRuntimeMarker(cl: String): Boolean = GRADLE_RUNTIME_MARKER.containsMatchIn(cl)
}
