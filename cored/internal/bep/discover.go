package bep

import (
	"path/filepath"
	"regexp"
	"strings"
)

// Format is the on-disk BEP encoding.
type Format string

const (
	FormatJSON   Format = "json"
	FormatBinary Format = "binary"
)

// Sink is one discovered BEP output file.
type Sink struct {
	Path   string
	Format Format
	// ClientPID is the bazel/bazelisk client that advertised the flag (0 if unknown).
	ClientPID int32
	// Cwd is the client working directory used to resolve relative paths.
	Cwd string
}

var (
	jsonFlag   = regexp.MustCompile(`--build_event_json_file=(?:"([^"]+)"|'([^']+)'|([^\s]+))`)
	binaryFlag = regexp.MustCompile(`--build_event_binary_file=(?:"([^"]+)"|'([^']+)'|([^\s]+))`)
)

// LooksLikeBazelClient reports whether cmdline is a bazel/bazelisk client
// invocation (not A-server.jar). Used to bound argv scraping.
func LooksLikeBazelClient(cmdline string) bool {
	if cmdline == "" {
		return false
	}
	lower := strings.ToLower(cmdline)
	if strings.Contains(lower, "a-server.jar") {
		return false
	}
	// Match path endings or bare tokens: /bazel, bazelisk, "bazel ", etc.
	fields := strings.Fields(cmdline)
	if len(fields) == 0 {
		return false
	}
	base := strings.ToLower(filepath.Base(fields[0]))
	base = strings.TrimSuffix(base, ".exe")
	if base == "bazel" || base == "bazelisk" {
		return true
	}
	// Some launches put the binary later (env wrappers).
	for _, f := range fields {
		b := strings.ToLower(filepath.Base(f))
		b = strings.TrimSuffix(b, ".exe")
		if b == "bazel" || b == "bazelisk" {
			return true
		}
	}
	return false
}

// ParseSinksFromArgv extracts BEP file sinks from a client command line.
// Relative paths are resolved against cwd when provided; unresolved relatives are skipped.
func ParseSinksFromArgv(cmdline, cwd string, clientPID int32) []Sink {
	if !LooksLikeBazelClient(cmdline) && !hasBEPFlag(cmdline) {
		return nil
	}
	out := make([]Sink, 0, 2)
	for _, m := range jsonFlag.FindAllStringSubmatch(cmdline, -1) {
		if path := firstGroup(m); path != "" {
			if resolved, ok := resolvePath(path, cwd); ok {
				out = append(out, Sink{Path: resolved, Format: FormatJSON, ClientPID: clientPID, Cwd: cwd})
			}
		}
	}
	for _, m := range binaryFlag.FindAllStringSubmatch(cmdline, -1) {
		if path := firstGroup(m); path != "" {
			if resolved, ok := resolvePath(path, cwd); ok {
				out = append(out, Sink{Path: resolved, Format: FormatBinary, ClientPID: clientPID, Cwd: cwd})
			}
		}
	}
	return out
}

func hasBEPFlag(cmdline string) bool {
	return strings.Contains(cmdline, "--build_event_json_file=") ||
		strings.Contains(cmdline, "--build_event_binary_file=")
}

func firstGroup(m []string) string {
	for _, g := range m[1:] {
		if g != "" {
			return g
		}
	}
	return ""
}

func resolvePath(path, cwd string) (string, bool) {
	path = strings.TrimSpace(path)
	if path == "" {
		return "", false
	}
	if filepath.IsAbs(path) {
		return filepath.Clean(path), true
	}
	if cwd == "" {
		return "", false
	}
	return filepath.Clean(filepath.Join(cwd, path)), true
}
