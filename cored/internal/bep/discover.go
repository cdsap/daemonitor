package bep

import (
	posixpath "path"
	"path/filepath"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/commandline"
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
	jsonFlag   = commandline.QuotedFlag("--build_event_json_file")
	binaryFlag = commandline.QuotedFlag("--build_event_binary_file")
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
	fields := strings.Fields(cmdline)
	if len(fields) == 0 {
		return false
	}
	// Only env assignments may precede the client executable. A Bazel token
	// in another program's arguments is not evidence of a Bazel client.
	if commandline.ExecutableBase(fields[0]) == "env" {
		fields = fields[1:]
		for len(fields) > 0 && strings.Contains(fields[0], "=") && !strings.HasPrefix(fields[0], "-") {
			fields = fields[1:]
		}
	}
	return len(fields) > 0 && (commandline.ExecutableBase(fields[0]) == "bazel" || commandline.ExecutableBase(fields[0]) == "bazelisk")
}

// ParseSinksFromArgv extracts BEP file sinks from a client command line.
// Relative paths are resolved against cwd when provided; unresolved relatives are skipped.
func ParseSinksFromArgv(cmdline, cwd string, clientPID int32) []Sink {
	if !LooksLikeBazelClient(cmdline) {
		return nil
	}
	out := make([]Sink, 0, 2)
	for _, m := range jsonFlag.FindAllStringSubmatch(cmdline, -1) {
		if path := commandline.FlagValue(m); path != "" {
			if resolved, ok := resolvePath(path, cwd); ok {
				out = append(out, Sink{Path: resolved, Format: FormatJSON, ClientPID: clientPID, Cwd: cwd})
			}
		}
	}
	for _, m := range binaryFlag.FindAllStringSubmatch(cmdline, -1) {
		if path := commandline.FlagValue(m); path != "" {
			if resolved, ok := resolvePath(path, cwd); ok {
				out = append(out, Sink{Path: resolved, Format: FormatBinary, ClientPID: clientPID, Cwd: cwd})
			}
		}
	}
	return out
}

func resolvePath(path, cwd string) (string, bool) {
	path = strings.TrimSpace(path)
	if path == "" {
		return "", false
	}
	// Bazel clients advertise POSIX absolute paths even when Daemonitor runs on
	// Windows; filepath.IsAbs("/tmp/x") is false there, so keep slash-absolute form.
	if strings.HasPrefix(path, "/") {
		return posixpath.Clean(path), true
	}
	if filepath.IsAbs(path) {
		return filepath.Clean(path), true
	}
	if cwd == "" {
		return "", false
	}
	return filepath.Clean(filepath.Join(cwd, path)), true
}
