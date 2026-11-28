package poll

import (
	"path"
	"strings"
)

const (
	TypeXcodeBuild        = "XCODE_BUILD"
	TypeXcodeBuildService = "XCODE_BUILD_SERVICE"
	TypeXcodeCompiler     = "XCODE_COMPILER"
	TypeXcodeTool         = "XCODE_TOOL"
)

// IsXcodeType reports whether kind is one of the native Xcode build process types.
func IsXcodeType(kind string) bool {
	switch kind {
	case TypeXcodeBuild, TypeXcodeBuildService, TypeXcodeCompiler, TypeXcodeTool:
		return true
	default:
		return false
	}
}

// ClassifyXcode classifies an Xcode build-execution process from its command line.
//
// Interactive Xcode.app GUI processes are intentionally excluded so the IDE itself
// is never grouped as a killable build worker. Generic tools such as clang, ld,
// codesign, and sh/bash are only matched when scoped to an Xcode toolchain path
// (or via live ancestor verification in ClassifyXcodeChild).
func ClassifyXcode(commandLine string) string {
	head := extractCommandHead(commandLine)
	if head == "" {
		return ""
	}
	base := pathBase(head)
	switch base {
	case "xcodebuild":
		return TypeXcodeBuild
	case "SWBBuildService", "XCBBuildService":
		return TypeXcodeBuildService
	case "swift-frontend", "swiftc":
		if isXcodeToolchainPath(head) || isCommandLineToolsPath(head) {
			return TypeXcodeCompiler
		}
		return ""
	case "clang", "clang++":
		if isXcodeAppPath(head) {
			return TypeXcodeCompiler
		}
		if isCommandLineToolsPath(head) && hasXcodeBuildArgs(commandLine) {
			return TypeXcodeCompiler
		}
		return ""
	case "ld", "ld-prime":
		if isXcodeAppPath(head) {
			return TypeXcodeTool
		}
		if isCommandLineToolsPath(head) && hasXcodeBuildArgs(commandLine) {
			return TypeXcodeTool
		}
		return ""
	case "actool", "ibtool", "ibtoold", "dsymutil", "momc", "mapc", "metal", "metallib",
		"appintentsmetadataprocessor", "clang-stat-cache", "swift-driver", "tapi":
		if isXcodeToolchainPath(head) {
			return TypeXcodeTool
		}
		return ""
	default:
		return ""
	}
}

// ClassifyXcodeChild classifies a process known to descend from an active
// XCODE_BUILD_SERVICE or XCODE_BUILD ancestor (for example /usr/bin/codesign or
// build-phase shell scripts that live outside Xcode.app).
func ClassifyXcodeChild(commandLine string) string {
	if kind := ClassifyXcode(commandLine); kind != "" {
		return kind
	}
	head := extractCommandHead(commandLine)
	if head == "" {
		return ""
	}
	switch pathBase(head) {
	case "swift-frontend", "swiftc", "clang", "clang++":
		return TypeXcodeCompiler
	case "ld", "ld-prime", "actool", "ibtool", "ibtoold", "dsymutil", "codesign",
		"momc", "mapc", "metal", "metallib", "appintentsmetadataprocessor", "clang-stat-cache",
		"swift-driver", "tapi", "lipo", "strip", "bitcode_strip",
		"plutil", "sh", "bash", "zsh":
		return TypeXcodeTool
	default:
		return ""
	}
}

// IsXcodeAnchor reports whether kind can anchor child Xcode build processes.
func IsXcodeAnchor(kind string) bool {
	return kind == TypeXcodeBuildService || kind == TypeXcodeBuild
}

// xcodeAnchorAncestor walks this poll's Pass 0 OS ancestry map to verify whether
// pid descends from an active XCODE_BUILD_SERVICE or XCODE_BUILD incarnation.
func xcodeAnchorAncestor(pid int32, nodes map[int32]processNode) bool {
	child, ok := nodes[pid]
	if !ok {
		return false
	}
	visited := map[int32]bool{pid: true}
	for depth := 0; depth < maxBazelAncestryDepth; depth++ {
		parentPID := child.parent
		if parentPID <= 1 || visited[parentPID] {
			return false
		}
		parent, ok := nodes[parentPID]
		if !ok {
			return false
		}
		if parent.start > 0 && child.start > 0 && parent.start > child.start {
			return false
		}
		if IsXcodeAnchor(parent.kind) {
			return true
		}
		visited[parentPID] = true
		child = parent
	}
	return false
}

// ExtractXcodeProjectPath derives a project/workspace path from xcodebuild argv
// (-workspace, -project, -package-path), falling back to cwd.
func ExtractXcodeProjectPath(commandLine string, cwd *string) *string {
	tokens := strings.Fields(commandLine)
	for i := 0; i < len(tokens)-1; i++ {
		switch tokens[i] {
		case "-workspace", "-project", "-package-path":
			candidate := strings.Trim(tokens[i+1], `"'`)
			if candidate == "" || strings.HasPrefix(candidate, "-") {
				continue
			}
			if !strings.HasPrefix(candidate, "/") && cwd != nil && *cwd != "" {
				candidate = path.Clean(path.Join(*cwd, candidate))
			}
			return &candidate
		}
	}
	return cwd
}

// extractCommandHead returns argv[0], handling quoted paths and macOS .app bundle
// paths whose app directory name contains spaces (e.g. "/Applications/Xcode 16.app/Contents/...").
func extractCommandHead(commandLine string) string {
	s := strings.TrimSpace(commandLine)
	if s == "" {
		return ""
	}
	if s[0] == '"' || s[0] == '\'' {
		quote := s[0]
		if end := strings.IndexByte(s[1:], quote); end >= 0 {
			return s[1 : 1+end]
		}
		return s[1:]
	}
	if idx := strings.Index(s, ".app/Contents/"); idx >= 0 {
		// Only treat .app/Contents/ as part of argv[0] if no flag (" -") appears before it.
		prefix := s[:idx]
		if !strings.Contains(prefix, " -") {
			restStart := idx + len(".app/Contents/")
			if sp := strings.IndexAny(s[restStart:], " \t\r\n"); sp >= 0 {
				return s[:restStart+sp]
			}
			return s
		}
	}
	if sp := strings.IndexAny(s, " \t\r\n"); sp >= 0 {
		return s[:sp]
	}
	return s
}

func pathBase(p string) string {
	p = strings.TrimRight(p, "/")
	if i := strings.LastIndexByte(p, '/'); i >= 0 {
		return p[i+1:]
	}
	return p
}

func isXcodeAppPath(head string) bool {
	return strings.Contains(head, ".app/Contents/Developer/") ||
		strings.Contains(head, ".xctoolchain/usr/bin/")
}

func isCommandLineToolsPath(head string) bool {
	return strings.Contains(head, "/Library/Developer/CommandLineTools/")
}

func isXcodeToolchainPath(head string) bool {
	return isXcodeAppPath(head) || isCommandLineToolsPath(head)
}

func hasXcodeBuildArgs(commandLine string) bool {
	return strings.Contains(commandLine, "DerivedData") ||
		strings.Contains(commandLine, ".platform/Developer/SDKs/") ||
		strings.Contains(commandLine, "XcodeDefault.xctoolchain")
}
