package poll_test

import (
	"testing"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/poll"
)

func TestClassify(t *testing.T) {
	cases := map[string]string{
		"/Library/Java/…/java org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10": "GRADLE_DAEMON",
		"java … org.jetbrains.kotlin.daemon.KotlinCompileDaemon":                       "KOTLIN_DAEMON",
		"java -jar /tmp/gradle/wrapper/gradle-wrapper.jar":                             "GRADLE_WRAPPER",
		"/bin/zsh": "",
	}
	for in, want := range cases {
		if got := poll.Classify(in); got != want {
			t.Fatalf("Classify(%q)=%q want %q", in, got, want)
		}
	}
}
