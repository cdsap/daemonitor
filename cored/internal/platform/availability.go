package platform

// Availability is the collection state for an optional metric.
// Unavailable metrics must use null values rather than fabricated zeros.
type Availability string

const (
	Available        Availability = "available"
	Unsupported      Availability = "unsupported"
	PermissionDenied Availability = "permission_denied"
	ToolUnavailable  Availability = "tool_unavailable"
	ProbeFailed      Availability = "probe_failed"
	Timeout          Availability = "timeout"
	Stale            Availability = "stale"
	NotCollected     Availability = "not_collected"
)

// Valid reports whether a is a known availability state.
func (a Availability) Valid() bool {
	switch a {
	case Available, Unsupported, PermissionDenied, ToolUnavailable,
		ProbeFailed, Timeout, Stale, NotCollected:
		return true
	default:
		return false
	}
}
