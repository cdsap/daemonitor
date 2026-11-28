package poll_test

import (
	"testing"

	"github.com/cdsap/daemonitor/cored/internal/poll"
)

func TestClassifyXcodeProcesses(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "xcodebuild usr bin",
			in:   "/usr/bin/xcodebuild -workspace App.xcworkspace -scheme App build",
			want: poll.TypeXcodeBuild,
		},
		{
			name: "xcodebuild inside Xcode app with spaces",
			in:   "/Applications/Xcode 27.2.app/Contents/Developer/usr/bin/xcodebuild -project MyApp.xcodeproj build",
			want: poll.TypeXcodeBuild,
		},
		{
			name: "SWBBuildService",
			in:   "/Applications/Xcode.app/Contents/SharedFrameworks/SwiftBuild.framework/Versions/A/PlugIns/SWBBuildService.bundle/Contents/MacOS/SWBBuildService",
			want: poll.TypeXcodeBuildService,
		},
		{
			name: "XCBBuildService",
			in:   "/Applications/Xcode.app/Contents/SharedFrameworks/XCBuild.framework/Versions/A/PlugIns/XCBBuildService.bundle/Contents/MacOS/XCBBuildService",
			want: poll.TypeXcodeBuildService,
		},
		{
			name: "swift-frontend in Xcode toolchain",
			in:   "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-frontend -frontend -c Foo.swift",
			want: poll.TypeXcodeCompiler,
		},
		{
			name: "swiftc in CommandLineTools",
			in:   "/Library/Developer/CommandLineTools/usr/bin/swiftc main.swift",
			want: poll.TypeXcodeCompiler,
		},
		{
			name: "clang in Xcode toolchain",
			in:   "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang -x objective-c -c App.m",
			want: poll.TypeXcodeCompiler,
		},
		{
			name: "ld in Xcode toolchain",
			in:   "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ld -demangle -o App",
			want: poll.TypeXcodeTool,
		},
		{
			name: "actool in Xcode Developer bin",
			in:   "/Applications/Xcode.app/Contents/Developer/usr/bin/actool --output-format human-readable-text Assets.xcassets",
			want: poll.TypeXcodeTool,
		},
		{
			name: "dsymutil in Xcode toolchain",
			in:   "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/dsymutil App.app/App",
			want: poll.TypeXcodeTool,
		},
		{
			name: "appintentsmetadataprocessor in Xcode toolchain",
			in:   "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/appintentsmetadataprocessor --output App.app",
			want: poll.TypeXcodeTool,
		},
		{
			name: "clang-stat-cache in Xcode toolchain",
			in:   "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang-stat-cache /SDK",
			want: poll.TypeXcodeTool,
		},
		{
			name: "Xcode IDE GUI is excluded",
			in:   "/Applications/Xcode.app/Contents/MacOS/Xcode",
			want: "",
		},
		{
			name: "generic usr bin clang without Xcode context is excluded",
			in:   "/usr/bin/clang -o hello hello.c",
			want: "",
		},
		{
			name: "CommandLineTools clang without Xcode args is excluded",
			in:   "/Library/Developer/CommandLineTools/usr/bin/clang -c foo.c",
			want: "",
		},
		{
			name: "unscoped codesign requires ancestor verification",
			in:   "/usr/bin/codesign --force --sign - App.app",
			want: "",
		},
		{
			name: "unscoped shell script requires ancestor verification",
			in:   "/bin/sh -c /tmp/script.sh",
			want: "",
		},
		{
			name: "grep mentioning xcodebuild is excluded",
			in:   "grep -E xcodebuild|SWBBuildService /tmp/out",
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

func TestClassifyXcodeChildAllowsAncestorVerifiedTools(t *testing.T) {
	if got := poll.ClassifyXcodeChild("/usr/bin/codesign --force --sign - App.app"); got != poll.TypeXcodeTool {
		t.Fatalf("codesign=%q want %q", got, poll.TypeXcodeTool)
	}
	if got := poll.ClassifyXcodeChild("/bin/sh -c /Users/dev/DerivedData/Script.sh"); got != poll.TypeXcodeTool {
		t.Fatalf("sh=%q want %q", got, poll.TypeXcodeTool)
	}
	if got := poll.ClassifyXcodeChild("/usr/bin/clang -c foo.c"); got != poll.TypeXcodeCompiler {
		t.Fatalf("clang=%q want %q", got, poll.TypeXcodeCompiler)
	}
	if got := poll.ClassifyXcodeChild("/Applications/Safari.app/Contents/MacOS/Safari"); got != "" {
		t.Fatalf("safari=%q want empty", got)
	}
}

func TestXcodeTypesNeverProbeJVMHeap(t *testing.T) {
	for _, kind := range []string{
		poll.TypeXcodeBuild,
		poll.TypeXcodeBuildService,
		poll.TypeXcodeCompiler,
		poll.TypeXcodeTool,
	} {
		if poll.ShouldProbeLiveHeap(kind, "") {
			t.Fatalf("%s must never trigger JVM heap probing", kind)
		}
	}
}

func TestExtractXcodeProjectPath(t *testing.T) {
	cwd := "/Users/dev/ios-app"
	got := poll.ExtractXcodeProjectPath("/usr/bin/xcodebuild -workspace App.xcworkspace -scheme App build", &cwd)
	if got == nil || *got != "/Users/dev/ios-app/App.xcworkspace" {
		t.Fatalf("relative workspace=%q", deref(got))
	}
	abs := poll.ExtractXcodeProjectPath("/usr/bin/xcodebuild -project /work/Foo/Foo.xcodeproj build", &cwd)
	if abs == nil || *abs != "/work/Foo/Foo.xcodeproj" {
		t.Fatalf("abs project=%q", deref(abs))
	}
	fallback := poll.ExtractXcodeProjectPath("/usr/bin/xcodebuild -scheme App build", &cwd)
	if fallback == nil || *fallback != cwd {
		t.Fatalf("fallback cwd=%q", deref(fallback))
	}
}

func deref(s *string) string {
	if s == nil {
		return "<nil>"
	}
	return *s
}
