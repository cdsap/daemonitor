// Package ipc provides local collector ↔ client transport.
//
// macOS/Linux use Unix domain sockets (0600). Windows uses named pipes with an
// ACL restricted to the current user (equivalent local-only access).
package ipc

import (
	"context"
	"fmt"
	"net"
	"strings"
)

// Transport listens and dials local IPC endpoints.
type Transport interface {
	Listen(endpoint string) (net.Listener, error)
	Dial(ctx context.Context, endpoint string) (net.Conn, error)
}

// Default is the OS-native transport.
var Default Transport = platform{}

// DefaultWindowsPipePath is the named-pipe endpoint used when GOOS=windows.
const DefaultWindowsPipePath = `\\.\pipe\daemonitor-core`

// IsNamedPipe reports whether endpoint is a Windows named-pipe path.
func IsNamedPipe(endpoint string) bool {
	lower := strings.ToLower(endpoint)
	return strings.HasPrefix(lower, `\\.\pipe\`) || strings.HasPrefix(lower, `//./pipe/`)
}

// NeedsEndpointDir is true when the endpoint lives on the filesystem and needs a parent directory.
func NeedsEndpointDir(endpoint string) bool {
	return endpoint != "" && !IsNamedPipe(endpoint)
}

// Scheme returns a short label for logs ("unix" or "npipe").
func Scheme(endpoint string) string {
	if IsNamedPipe(endpoint) {
		return "npipe"
	}
	return "unix"
}

// pipeSDDLForUserSID builds an SDDL that grants Generic All to one user SID only.
// Protected DACL (D:P) blocks inherited ACEs — local-only, analogous to Unix 0600.
func pipeSDDLForUserSID(sid string) string {
	return fmt.Sprintf("D:P(A;;GA;;;%s)", sid)
}
