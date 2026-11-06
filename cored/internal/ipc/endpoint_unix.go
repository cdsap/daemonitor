//go:build !windows

package ipc

// DefaultWindowsEndpoint is the Windows default; on non-Windows builds it returns
// the legacy constant for path-selection unit tests.
func DefaultWindowsEndpoint() string {
	return DefaultWindowsPipePath
}
