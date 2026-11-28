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

            // Kotlin Toolchain CLI (Amper). Distinct from KOTLIN_DAEMON (KotlinCompileDaemon under Gradle).
            cl.contains("org.jetbrains.amper.cli.MainKt") -> ProcessType.KOTLIN_TOOLCHAIN

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

            else -> classifyXcode(cl)
        }
    }

    fun isXcodeType(type: ProcessType): Boolean = when (type) {
        ProcessType.XCODE_BUILD,
        ProcessType.XCODE_BUILD_SERVICE,
        ProcessType.XCODE_COMPILER,
        ProcessType.XCODE_TOOL,
        -> true
        else -> false
    }

    /**
     * Classifies an Xcode build-execution process from its command line (mirrors Go `ClassifyXcode`).
     * Excludes interactive `Xcode.app` GUI and unscoped generic compilers/tools.
     */
    fun classifyXcode(commandLine: String): ProcessType? {
        val head = extractCommandHead(commandLine)
        if (head.isEmpty()) return null
        return when (pathBase(head)) {
            "xcodebuild" -> ProcessType.XCODE_BUILD
            "SWBBuildService", "XCBBuildService" -> ProcessType.XCODE_BUILD_SERVICE
            "swift-frontend", "swiftc" ->
                if (isXcodeToolchainPath(head) || isCommandLineToolsPath(head)) ProcessType.XCODE_COMPILER else null
            "clang", "clang++" -> when {
                isXcodeAppPath(head) -> ProcessType.XCODE_COMPILER
                isCommandLineToolsPath(head) && hasXcodeBuildArgs(commandLine) -> ProcessType.XCODE_COMPILER
                else -> null
            }
            "ld", "ld-prime" -> when {
                isXcodeAppPath(head) -> ProcessType.XCODE_TOOL
                isCommandLineToolsPath(head) && hasXcodeBuildArgs(commandLine) -> ProcessType.XCODE_TOOL
                else -> null
            }
            "actool", "ibtool", "ibtoold", "dsymutil", "momc", "mapc", "metal", "metallib",
            "appintentsmetadataprocessor", "clang-stat-cache", "swift-driver", "tapi",
            -> if (isXcodeToolchainPath(head)) ProcessType.XCODE_TOOL else null
            else -> null
        }
    }

    fun extractXcodeProjectPath(commandLine: String, cwd: String?): String? {
        val tokens = commandLine.trim().split(Regex("""\s+"""))
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] in setOf("-workspace", "-project", "-package-path")) {
                val candidate = tokens[i + 1].trim('"', '\'')
                if (candidate.isEmpty() || candidate.startsWith("-")) continue
                return if (!candidate.startsWith("/") && !cwd.isNullOrBlank()) {
                    joinPosixPath(cwd, candidate)
                } else {
                    candidate
                }
            }
        }
        return cwd
    }

    private fun joinPosixPath(base: String, relative: String): String {
        val raw = "${base.trimEnd('/')}/$relative"
        val absolute = raw.startsWith("/")
        val parts = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (parts.isNotEmpty() && parts.last() != "..") {
                    parts.removeLast()
                } else if (!absolute) {
                    parts.addLast(segment)
                }
                else -> parts.addLast(segment)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined.ifEmpty { "." }
    }

    private fun extractCommandHead(commandLine: String): String {
        val s = commandLine.trim()
        if (s.isEmpty()) return ""
        if (s[0] == '"' || s[0] == '\'') {
            val end = s.indexOf(s[0], 1)
            return if (end > 0) s.substring(1, end) else s.substring(1)
        }
        val appIdx = s.indexOf(".app/Contents/")
        if (appIdx >= 0 && !s.substring(0, appIdx).contains(" -")) {
            val restStart = appIdx + ".app/Contents/".length
            val sp = s.indexOfAny(charArrayOf(' ', '\t', '\r', '\n'), restStart)
            return if (sp >= 0) s.substring(0, sp) else s
        }
        val sp = s.indexOfAny(charArrayOf(' ', '\t', '\r', '\n'))
        return if (sp >= 0) s.substring(0, sp) else s
    }

    private fun pathBase(p: String): String = p.trimEnd('/').substringAfterLast('/')

    private fun isXcodeAppPath(head: String): Boolean =
        head.contains(".app/Contents/Developer/") || head.contains(".xctoolchain/usr/bin/")

    private fun isCommandLineToolsPath(head: String): Boolean =
        head.contains("/Library/Developer/CommandLineTools/")

    private fun isXcodeToolchainPath(head: String): Boolean =
        isXcodeAppPath(head) || isCommandLineToolsPath(head)

    private fun hasXcodeBuildArgs(commandLine: String): Boolean =
        commandLine.contains("DerivedData") ||
            commandLine.contains(".platform/Developer/SDKs/") ||
            commandLine.contains("XcodeDefault.xctoolchain")

    private fun containsGradlewInvocation(cl: String): Boolean =
        GRADLEW_INVOCATION.containsMatchIn(cl)

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
        val sandboxed = BAZEL_SANDBOX_PATH.containsMatchIn(commandLine)
        val runfiles = BAZEL_RUNFILES_PATH.containsMatchIn(commandLine)
        return sandboxed && runfiles
    }

    /** Promote a native sandboxed runfiles binary when its parent is already `BAZEL_TEST`. */
    fun promoteBazelNativeTest(commandLine: String, parentType: ProcessType?): ProcessType? =
        if (parentType == ProcessType.BAZEL_TEST && isBazelNativeTestCandidate(commandLine)) {
            ProcessType.BAZEL_TEST
        } else {
            null
        }

    /** Bazel persistent workers (JavaBuilder / Kotlin / Swift), distinct from [BAZEL_TEST]. */
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
        val match = BAZEL_WORKSPACE_DIR
            .find(commandLine)
            ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun isGradleWorker(cl: String): Boolean =
        cl.contains("worker.org.gradle.process.internal.worker.GradleWorkerMain") ||
            cl.contains("org.gradle.process.internal.worker.GradleWorkerMain")

    private fun classifyGradleWorker(cl: String): ProcessType = when {
        cl.contains("Gradle Test Executor") -> ProcessType.TEST_WORKER
        cl.contains("Gradle Worker Daemon") -> ProcessType.GRADLE_WORKER
        else -> ProcessType.UNKNOWN_GRADLE_WORKER
    }

    private fun isJava(cl: String): Boolean =
        JAVA_EXECUTABLE.containsMatchIn(cl)

    private val GRADLEW_INVOCATION = Regex("""(^|[\s/])gradlew(\s|$)""")
    private val BAZEL_SERVER_JAR = Regex("""(^|[\s/\\])A-server\.jar["']?(\s|$)""")
    private val BAZEL_PRODUCT_NAME = Regex("""--product_name=Bazel(\s|$)""")
    private val BAZEL_TEST_SETUP = Regex("""(^|[\s/\\])tools/test/test-setup\.sh(\s|$)""")
    private val BAZEL_PERSISTENT_WORKER = Regex("""--persistent_worker(\s|$)""")
    private val BAZEL_SANDBOX_PATH = Regex(
        """(^|[\s/\\])(?:darwin|linux|processwrapper)-sandbox(/|\\)|(^|[\s/\\])sandbox(/|\\)""",
    )
    private val BAZEL_RUNFILES_PATH = Regex("""\.runfiles(/|\\)""")
    private val BAZEL_WORKSPACE_DIR = Regex("""--workspace_directory=(?:"([^"]+)"|'([^']+)'|([^\s]+))""")
    private val JAVA_EXECUTABLE = Regex("""(^|[\s/])java(\s|$)""")

    // A genuine Gradle *runtime* signal — a Gradle system property, a Gradle launcher/worker/tooling
    // class, or the worker main. Deliberately does NOT match a bare "~/.gradle/caches/…" classpath
    // path: every JVM whose dependencies were fetched by Gradle has that, which over-detected any
    // Gradle-built app (including this watcher itself).
    private val GRADLE_RUNTIME_MARKER = Regex(
        """(-Dorg\.gradle\.|org\.gradle\.(launcher|process|tooling|internal|api|workers)\.|GradleWorkerMain)""",
    )

    private fun hasGradleRuntimeMarker(cl: String): Boolean = GRADLE_RUNTIME_MARKER.containsMatchIn(cl)
}
