package tui

import (
	"strings"
	"testing"

	terminalansi "github.com/charmbracelet/x/ansi"
)

func TestRenderCliDocsScreenshotShowsGradleAndBazelTable(t *testing.T) {
	body := terminalansi.Strip(RenderCliDocsScreenshot())
	for _, want := range []string{
		"DAEMONITOR",
		"Bazel server",
		"Gradle daemon",
		"Kotlin daemon",
		"Bazel worker",
		"Gradle worker",
		"Bazel test",
		"Gradle wrapper",
		"7102",
		"6120",
		"maps-service",
		"checkout-service",
		"android-app",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("docs screenshot missing %q:\n%s", want, body)
		}
	}
	if strings.Contains(body, "/Users/") || strings.Contains(body, "/home/") {
		t.Fatalf("docs screenshot leaked a real home path:\n%s", body)
	}
	if strings.Contains(body, "PROCESS DETAILS") {
		t.Fatalf("docs screenshot should be the main process table, not details:\n%s", body)
	}
}
