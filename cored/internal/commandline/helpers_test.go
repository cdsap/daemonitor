package commandline

import "testing"

func TestQuotedFlags(t *testing.T) {
	flag := QuotedFlag("--workspace_directory")
	for _, tc := range []struct{ command, want string }{
		{`java --workspace_directory=/tmp/project`, "/tmp/project"},
		{`java --workspace_directory="/tmp/my project" --other=x`, "/tmp/my project"},
		{`java --workspace_directory='/tmp/my project'`, "/tmp/my project"},
		{`java --other=/tmp/project`, ""},
	} {
		if got := FlagValue(flag.FindStringSubmatch(tc.command)); got != tc.want {
			t.Errorf("%q: got %q want %q", tc.command, got, tc.want)
		}
	}
}

func TestExecutableBase(t *testing.T) {
	for _, tc := range []struct{ path, want string }{
		{"/usr/bin/java", "java"}, {`C:\Java\bin\JAVA.EXE`, "java"},
		{`C:\tools\Bazelisk.exe`, "bazelisk"}, {"/usr/local/bin/BAZEL", "bazel"},
	} {
		if got := ExecutableBase(tc.path); got != tc.want {
			t.Errorf("%q: got %q want %q", tc.path, got, tc.want)
		}
	}
}
