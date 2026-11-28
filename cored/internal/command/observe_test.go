package command

import (
	"reflect"
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

func TestParseArgsObservePreservesChildFlagsAfterDoubleDash(t *testing.T) {
	opts, err := ParseArgs([]string{
		"observe", "--json", "--socket", "/tmp/test.sock", "--",
		"xcodebuild", "-workspace", "App.xcworkspace", "-scheme", "App", "-configuration", "Debug", "build",
	})
	if err != nil {
		t.Fatalf("ParseArgs failed: %v", err)
	}
	if !opts.JSON || opts.Output != "json" || opts.Socket != "/tmp/test.sock" {
		t.Fatalf("unexpected opts: %+v", opts)
	}
	wantArgs := []string{"observe"}
	if !reflect.DeepEqual(opts.Args, wantArgs) {
		t.Fatalf("Args=%v want %v", opts.Args, wantArgs)
	}
	wantCmd := []string{"xcodebuild", "-workspace", "App.xcworkspace", "-scheme", "App", "-configuration", "Debug", "build"}
	if !reflect.DeepEqual(opts.CommandArgs, wantCmd) {
		t.Fatalf("CommandArgs=%v want %v", opts.CommandArgs, wantCmd)
	}
}

func TestParseArgsSessionsSupportsEcosystemFilter(t *testing.T) {
	opts, err := ParseArgs([]string{"sessions", "--ecosystem", "xcode", "--limit", "20", "--json"})
	if err != nil {
		t.Fatalf("ParseArgs failed: %v", err)
	}
	if opts.Ecosystem != "xcode" || opts.Limit != 20 || opts.Output != "json" {
		t.Fatalf("unexpected opts: %+v", opts)
	}
}

func TestFilterProcessesByEcosystemSeparatesGradleBazelAndXcode(t *testing.T) {
	items := []model.Process{
		{PID: 1, Type: "GRADLE_DAEMON"},
		{PID: 2, Type: "XCODE_BUILD"},
		{PID: 3, Type: "XCODE_COMPILER"},
		{PID: 4, Type: "BAZEL_SERVER"},
	}
	xcodeOnly := filterProcesses(append([]model.Process(nil), items...), Options{Ecosystem: "xcode"})
	if len(xcodeOnly) != 2 || xcodeOnly[0].PID != 2 || xcodeOnly[1].PID != 3 {
		t.Fatalf("xcodeOnly=%+v", xcodeOnly)
	}
	gradleOnly := filterProcesses(append([]model.Process(nil), items...), Options{Ecosystem: "gradle"})
	if len(gradleOnly) != 1 || gradleOnly[0].PID != 1 {
		t.Fatalf("gradleOnly=%+v", gradleOnly)
	}
	bazelOnly := filterProcesses(append([]model.Process(nil), items...), Options{Ecosystem: "bazel"})
	if len(bazelOnly) != 1 || bazelOnly[0].PID != 4 {
		t.Fatalf("bazelOnly=%+v", bazelOnly)
	}
}
