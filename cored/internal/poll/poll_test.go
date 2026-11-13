package poll_test

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/poll"
)

// Fixtures mirror core/.../GradleProcessClassifierTest.kt
func TestClassifyParityWithKotlin(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "gradle daemon",
			in:   "java -cp gradle-launcher.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3",
			want: "GRADLE_DAEMON",
		},
		{
			name: "gradle wrapper shell",
			in:   "/bin/sh /Users/dev/proj/gradlew build",
			want: "GRADLE_WRAPPER",
		},
		{
			name: "jar-launched wrapper JVM",
			in: "java -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew " +
				"-jar /Users/dev/proj/gradle/wrapper/gradle-wrapper.jar build",
			want: "GRADLE_WRAPPER",
		},
		{
			name: "kotlin daemon",
			in:   "java -cp kotlin-daemon.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon",
			want: "KOTLIN_DAEMON",
		},
		{
			name: "gradle test worker",
			in:   "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'",
			want: "TEST_WORKER",
		},
		{
			name: "generic gradle worker",
			in:   "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Worker Daemon 11'",
			want: "GRADLE_WORKER",
		},
		{
			name: "unknown gradle worker",
			in:   "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Custom Worker 1'",
			want: "UNKNOWN_GRADLE_WORKER",
		},
		{
			name: "java with gradle runtime marker",
			in:   "java -Dorg.gradle.internal.worker=1 -cp /x/foo.jar com.example.Tool",
			want: "JAVA_GRADLE_RELATED",
		},
		{
			name: "java tooling api",
			in:   "java -cp /x/gradle-tooling-api.jar org.gradle.tooling.internal.Foo",
			want: "JAVA_GRADLE_RELATED",
		},
		{
			name: "gradle cache path only is not related",
			in:   "java -Dcompose.application=true -cp /Users/dev/.gradle/caches/oshi-core.jar io.github.cdsap.daemonitor.MainKt",
			want: "",
		},
		{
			name: "safari",
			in:   "/Applications/Safari.app/Contents/MacOS/Safari",
			want: "",
		},
		{
			name: "node",
			in:   "node server.js",
			want: "",
		},
		{
			name: "bazel server macOS argv",
			in: "bazel(demo) -jar /Users/dev/Library/Caches/bazel/_bazel_dev/install/abc/A-server.jar " +
				"--output_base=/Users/dev/Library/Caches/bazel/_bazel_dev/deadbeef " +
				"--workspace_directory=/private/tmp/ws --product_name=Bazel",
			want: "BAZEL_SERVER",
		},
		{
			name: "bazel server product_name only with jar",
			in:   "java -jar /x/install/A-server.jar --workspace_directory=/tmp/ws --product_name=Bazel",
			want: "BAZEL_SERVER",
		},
		{
			name: "bazel server output_base without product_name",
			in:   "java -jar /x/install/A-server.jar --output_base=/tmp/out --workspace_directory=/tmp/ws",
			want: "BAZEL_SERVER",
		},
		{
			name: "bazel server windows path separators",
			in:   `java -jar C:\Users\dev\.cache\bazel\install\abc\A-server.jar --product_name=Bazel --workspace_directory=C:\ws`,
			want: "BAZEL_SERVER",
		},
		{
			name: "bazel server quoted jar path",
			in:   `java -jar "/Users/dev/Library/Caches/bazel/install/abc/A-server.jar" --product_name=Bazel --workspace_directory=/tmp/ws`,
			want: "BAZEL_SERVER",
		},
		{
			name: "A-server jar without bazel markers is not bazel",
			in:   "java -jar /opt/other/A-server.jar --config=/tmp/x",
			want: "",
		},
		{
			name: "product_name Bazelisk is not Bazel server",
			in:   "java -jar /x/A-server.jar --product_name=Bazelisk --workspace_directory=/tmp/ws",
			want: "",
		},
		{
			name: "bazel JVM test (BazelTestRunner)",
			in: "/path/sandbox/darwin-sandbox/20/execroot/_main/bazel-out/bin/SlowTest.runfiles/" +
				"rules_java++toolchains+local_jdk/bin/java -classpath SlowTest.jar:Runner_deploy.jar " +
				"-Dbazel.test_suite=demo.SlowTest com.google.testing.junit.runner.BazelTestRunner",
			want: "BAZEL_TEST",
		},
		{
			name: "bazel test-setup.sh spine",
			in:   "bash external/bazel_tools/tools/test/test-setup.sh ./SlowTest",
			want: "BAZEL_TEST",
		},
		{
			name: "bazel process-wrapper invoking test-setup",
			in: "/Users/dev/Library/Caches/bazel/_bazel_dev/install/abc/process-wrapper --timeout=60 " +
				"--kill_delay=15 external/bazel_tools/tools/test/test-setup.sh ./SlowTest",
			want: "BAZEL_TEST",
		},
		{
			name: "bazel native go test under sandbox runfiles needs parent (argv alone ignored)",
			in: "/Users/dev/Library/Caches/bazel/_bazel_dev/hash/sandbox/darwin-sandbox/11/execroot/_main/" +
				"bazel-out/darwin_arm64-fastbuild/bin/slow_test_/slow_test.runfiles/_main/slow_test_/slow_test",
			want: "",
		},
		{
			name: "bazel JavaBuilder persistent worker",
			in: "/path/execroot/_main/external/rules_java++toolchains+remotejdk25_macos_aarch64/bin/java " +
				"-jar external/rules_java++toolchains+remote_java_tools/java_tools/JavaBuilder_deploy.jar --persistent_worker",
			want: "BAZEL_WORKER",
		},
		{
			name: "bazel Kotlin builder persistent worker",
			in: "/path/_bazel_dev/execroot/_main/bazel-out/bin/external/rules_kotlin+/src/main/kotlin/build.runfiles/" +
				"rules_java++toolchains+remotejdk11_macos_aarch64/bin/java -classpath build.jar " +
				"io.bazel.kotlin.builder.cmd.Build --persistent_worker",
			want: "BAZEL_WORKER",
		},
		{
			name: "bazel Swift persistent worker",
			in: "/Users/dev/Library/Caches/bazel/_bazel_dev/hash/execroot/_main/bazel-out/darwin_arm64-opt-exec/" +
				"bin/external/rules_swift+/tools/worker/worker swiftc --persistent_worker",
			want: "BAZEL_WORKER",
		},
		{
			name: "persistent_worker without bazel marker is ignored",
			in:   "java -jar /opt/other/tool.jar --persistent_worker",
			want: "",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := poll.Classify(tc.in); got != tc.want {
				t.Fatalf("Classify(%q)=%q want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestBazelWorkspaceDirectory(t *testing.T) {
	in := "-jar /x/A-server.jar --workspace_directory=/private/tmp/ws --product_name=Bazel"
	if got := poll.BazelWorkspaceDirectory(in); got != "/private/tmp/ws" {
		t.Fatalf("got %q", got)
	}
	quoted := `-jar /x/A-server.jar --workspace_directory="/tmp/my ws" --product_name=Bazel`
	if got := poll.BazelWorkspaceDirectory(quoted); got != "/tmp/my ws" {
		t.Fatalf("quoted got %q", got)
	}
}

func TestShouldProbeLiveHeapIncludesBazelFamily(t *testing.T) {
	for _, kind := range []string{"BAZEL_SERVER", "BAZEL_TEST", "BAZEL_WORKER"} {
		if !poll.ShouldProbeLiveHeap(kind) {
			t.Fatalf("%s should be heap-probed", kind)
		}
	}
}

func TestPromoteBazelNativeTest(t *testing.T) {
	native := "/Users/dev/Library/Caches/bazel/_bazel_dev/hash/sandbox/darwin-sandbox/11/execroot/_main/" +
		"bazel-out/darwin_arm64-fastbuild/bin/slow_test_/slow_test.runfiles/_main/slow_test_/slow_test"
	if got := poll.PromoteBazelNativeTest(native, "BAZEL_TEST"); got != "BAZEL_TEST" {
		t.Fatalf("PromoteBazelNativeTest(parent=BAZEL_TEST)=%q", got)
	}
	if got := poll.PromoteBazelNativeTest(native, "BAZEL_SERVER"); got != "" {
		t.Fatalf("direct server child must not promote: %q", got)
	}
	if got := poll.PromoteBazelNativeTest(native, ""); got != "" {
		t.Fatalf("argv alone must not promote: %q", got)
	}
	worker := "/path/sandbox/darwin-sandbox/1/execroot/_main/build.runfiles/x/bin/java --persistent_worker"
	if got := poll.PromoteBazelNativeTest(worker, "BAZEL_TEST"); got != "" {
		t.Fatalf("persistent worker must not promote as test: %q", got)
	}
}

func TestParseJVMArgsParity(t *testing.T) {
	args := poll.ParseJVMArgs("java -Xmx4096m -Xms512m -XX:+UseG1GC -cp x.jar Main")
	if args.MaxHeapMB == nil || *args.MaxHeapMB != 4096 {
		t.Fatalf("max=%v", args.MaxHeapMB)
	}
	if args.MinHeapMB == nil || *args.MinHeapMB != 512 {
		t.Fatalf("min=%v", args.MinHeapMB)
	}
	if args.GC == nil || *args.GC != "G1" {
		t.Fatalf("gc=%v", args.GC)
	}
}

func TestIsNonInteractive(t *testing.T) {
	if !poll.IsNonInteractive("./gradlew build --non-interactive") {
		t.Fatal("expected non-interactive")
	}
	if poll.IsNonInteractive("./gradlew build") {
		t.Fatal("expected interactive")
	}
}

func TestShouldProbeLiveHeap(t *testing.T) {
	for _, kind := range []string{"GRADLE_DAEMON", "KOTLIN_DAEMON", "GRADLE_WORKER", "TEST_WORKER", "UNKNOWN_GRADLE_WORKER", "JAVA_GRADLE_RELATED", "BAZEL_SERVER", "BAZEL_TEST", "BAZEL_WORKER"} {
		if !poll.ShouldProbeLiveHeap(kind) {
			t.Fatalf("%s must be probed", kind)
		}
	}
	for _, kind := range []string{"GRADLE_WRAPPER", ""} {
		if poll.ShouldProbeLiveHeap(kind) {
			t.Fatalf("%s must stay unprobed", kind)
		}
	}
}
