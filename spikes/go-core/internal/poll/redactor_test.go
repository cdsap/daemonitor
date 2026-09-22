package poll_test

import (
	"strings"
	"testing"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/poll"
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
