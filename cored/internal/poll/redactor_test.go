package poll_test

import (
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/poll"
)

// Fixtures mirror core/.../RedactorTest.kt
func TestRedactCommandLineParity(t *testing.T) {
	cmd := "gradlew publish -Psigning.password=abc -Dtoken=xyz -PsafeFlag=ok --info"
	out := poll.RedactCommandLine(cmd)
	if !strings.Contains(out, "-Psigning.password=***") {
		t.Fatalf("missing password mask: %s", out)
	}
	if !strings.Contains(out, "-Dtoken=***") {
		t.Fatalf("missing token mask: %s", out)
	}
	if !strings.Contains(out, "-PsafeFlag=ok") {
		t.Fatalf("safe flag changed: %s", out)
	}
	if !strings.Contains(out, "--info") {
		t.Fatalf("info flag missing: %s", out)
	}
	if strings.Contains(out, "abc") || strings.Contains(out, "xyz") {
		t.Fatalf("secret leaked: %s", out)
	}
}

func TestRedactTokenLongOption(t *testing.T) {
	got := poll.RedactToken("--repository-password=hunter2")
	if got != "--repository-password=***" {
		t.Fatalf("got %q", got)
	}
}

func TestRedactTokenURLCredentials(t *testing.T) {
	got := poll.RedactToken("https://user:pw@repo.example.com/path")
	if got != "https://***:***@repo.example.com/path" {
		t.Fatalf("got %q", got)
	}
}

func TestRedactTokenLeavesSafe(t *testing.T) {
	if poll.RedactToken("clean") != "clean" {
		t.Fatal("clean changed")
	}
	if poll.RedactToken("-Dfile.encoding=UTF-8") != "-Dfile.encoding=UTF-8" {
		t.Fatal("encoding changed")
	}
}

func TestRedactCommandLineTabs(t *testing.T) {
	out := poll.RedactCommandLine("java\t-Dapi.token=SECRET\t-jar app.jar")
	if !strings.Contains(out, "-Dapi.token=***") {
		t.Fatalf("missing mask: %s", out)
	}
	if strings.Contains(out, "SECRET") {
		t.Fatalf("secret leaked: %s", out)
	}
}

func TestRedactCommandLineMasksValueFollowingSensitiveOption(t *testing.T) {
	got := poll.RedactCommandLine("java --password secret --token=inline --safe value")
	if got != "java --password *** --token=*** --safe value" {
		t.Fatalf("redacted command line=%q", got)
	}
}

func TestRedactDoesNotOverRedactKeySubstring(t *testing.T) {
	if poll.RedactToken("-Dmonkey.count=5") != "-Dmonkey.count=5" {
		t.Fatal("monkey.count over-redacted")
	}
	if poll.RedactToken("-Dkeystore.path=/x") != "-Dkeystore.path=/x" {
		t.Fatal("keystore.path over-redacted")
	}
	if poll.RedactToken("-Psigning.key=abc") != "-Psigning.key=***" {
		t.Fatal("signing.key not masked")
	}
}

func TestRedactLogLine(t *testing.T) {
	line := "2026-06-23T10:25:24 [INFO] [Build] running with -Papikey=deadbeef now"
	out := poll.RedactLogLine(line)
	if !strings.Contains(out, "-Papikey=***") {
		t.Fatalf("missing mask: %s", out)
	}
	if strings.Contains(out, "deadbeef") {
		t.Fatalf("secret leaked: %s", out)
	}
}

// Fixtures mirror RedactorTest.kt.
func TestRedactNestedTokenParity(t *testing.T) {
	for _, tt := range []struct{ input, want string }{
		{"--action_env=GITHUB_TOKEN=alpha", "--action_env=GITHUB_TOKEN=***"},
		{"--repo_env=API_SECRET=beta", "--repo_env=API_SECRET=***"},
		{"--test_env=DB_PASSWORD=gamma", "--test_env=DB_PASSWORD=***"},
		{"--client_env=SECRET_KEY=delta", "--client_env=SECRET_KEY=***"},
		{"--jvmopt=-Dmy.token=epsilon", "--jvmopt=-Dmy.token=***"},
		{"--host_jvmopt=-Dsigning.key=zeta", "--host_jvmopt=-Dsigning.key=***"},
		{"--jvmopt=--host_jvmopt=-Dmy.token=alpha=beta", "--jvmopt=--host_jvmopt=-Dmy.token=***"},
		{"--action_env=PATH=/bin", "--action_env=PATH=/bin"},
		{"--jvmopt=-Dmonkey.count=5", "--jvmopt=-Dmonkey.count=5"},
		{"--host_jvmopt=-Dkeystore.path=/x", "--host_jvmopt=-Dkeystore.path=/x"},
		{"--repo_env=ENDPOINT=https://user:pw@host/path", "--repo_env=ENDPOINT=https://***:***@host/path"},
		{"--safe=GITHUB_TOKEN=example", "--safe=GITHUB_TOKEN=example"},
	} {
		if got := poll.RedactToken(tt.input); got != tt.want {
			t.Errorf("input %q: got %q, want %q", tt.input, got, tt.want)
		}
		if got := poll.RedactCommandLine("bazel " + tt.input + " --info"); got != "bazel "+tt.want+" --info" {
			t.Errorf("command input %q: got %q", tt.input, got)
		}
		if got := poll.RedactLogLine("[INFO] " + tt.input + " done"); got != "[INFO] "+tt.want+" done" {
			t.Errorf("log input %q: got %q", tt.input, got)
		}
	}
}

// Fixtures mirror RedactorTest.kt.
func TestRedactSensitiveOptionParity(t *testing.T) {
	for _, tt := range []struct{ input, want string }{
		{"java --password secret --token=inline --safe value", "java --password *** --token=*** --safe value"},
		{"java\t--token\talpha -Psigning.key beta -Dmy.password gamma --safe ok", "java --token *** -Psigning.key *** -Dmy.password *** --safe ok"},
		{"java --monkey.count 5 --keystore.path /x --token", "java --monkey.count 5 --keystore.path /x --token"},
		{" \t ", " \t "},
	} {
		if got := poll.RedactCommandLine(tt.input); got != tt.want {
			t.Errorf("input %q: got %q, want %q", tt.input, got, tt.want)
		}
	}
}

// Fixtures mirror RedactorTest.kt.
func TestRedactLogCredentialsParity(t *testing.T) {
	for _, tt := range []struct{ input, want string }{
		{"Authorization: Bearer abc.def_123-xyz", "Authorization: Bearer ***"},
		{"Proxy-Authorization: Basic dXNlcjpwYXNz==", "Proxy-Authorization: Basic ***"},
		{"received bearer\tabc and BASIC xyz== now", "received bearer\t*** and BASIC *** now"},
		{"  SECRET_KEY=alpha\tDB_PASSWORD=beta API_TOKEN=gamma=delta PATH=/bin  ", "  SECRET_KEY=***\tDB_PASSWORD=*** API_TOKEN=*** PATH=/bin  "},
		{"secret_key=alpha monkey=5 keystore=/x", "secret_key=*** monkey=5 keystore=/x"},
		{"Authorization: Bearer", "Authorization: Bearer"},
		{"ordinary log\twith  spacing", "ordinary log\twith  spacing"},
	} {
		if got := poll.RedactLogLine(tt.input); got != tt.want {
			t.Errorf("input %q: got %q, want %q", tt.input, got, tt.want)
		}
	}
}
