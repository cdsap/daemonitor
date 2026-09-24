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
			name: "test worker",
			in:   "java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 3'",
			want: "TEST_WORKER",
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
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := poll.Classify(tc.in); got != tc.want {
				t.Fatalf("Classify(%q)=%q want %q", tc.in, got, tc.want)
			}
		})
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
	if !poll.ShouldProbeLiveHeap("GRADLE_DAEMON") || !poll.ShouldProbeLiveHeap("KOTLIN_DAEMON") {
		t.Fatal("daemons must be probed")
	}
	for _, kind := range []string{"GRADLE_WRAPPER", "TEST_WORKER", "JAVA_GRADLE_RELATED", ""} {
		if poll.ShouldProbeLiveHeap(kind) {
			t.Fatalf("%s must stay unprobed", kind)
		}
	}
}
