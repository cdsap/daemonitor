package tui

import (
	"strings"
	"testing"

	terminalansi "github.com/charmbracelet/x/ansi"
)

func TestRenderCliDocsScreenshotShowsBazelServerAndBEPBuilds(t *testing.T) {
	body := terminalansi.Strip(RenderCliDocsScreenshot())
	for _, want := range []string{
		"BAZEL SERVER",
		"Bazel server",
		"pid 7102",
		"maps-service",
		"/workspace/samples/maps-service",
		"RECENT BUILDS",
		"SUCCESS",
		"FAILED",
		"source=bazel/BEP",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("docs screenshot missing %q:\n%s", want, body)
		}
	}
	if strings.Contains(body, "/Users/") || strings.Contains(body, "/home/") {
		t.Fatalf("docs screenshot leaked a real home path:\n%s", body)
	}
}
