//go:build windows

package ipc

// DefaultWindowsEndpoint returns the per-user named-pipe path for this session.
// Falls back to the legacy well-known name only if the user SID cannot be resolved.
func DefaultWindowsEndpoint() string {
	sid, err := currentUserSID()
	if err != nil || sid == "" {
		return DefaultWindowsPipePath
	}
	return WindowsPipePathForUserSID(sid)
}
