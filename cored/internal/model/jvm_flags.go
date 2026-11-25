package model

// JVMFlagsMetadata is a separate, versioned per-incarnation document. Values
// are canonical tool strings so large uint64 values survive JSON consumers.
type JVMFlagsMetadata struct {
	SchemaVersion int                `json:"schema_version"`
	PID           int32              `json:"pid"`
	StartTimeMs   int64              `json:"start_time_ms"`
	CollectedAtMs int64              `json:"collected_at_ms"`
	Source        string             `json:"source"`
	Availability  string             `json:"availability"`
	Reason        string             `json:"reason,omitempty"`
	PrivacyPolicy string             `json:"privacy_policy"`
	Incomplete    bool               `json:"incomplete"`
	OmittedCount  int                `json:"omitted_count"`
	Flags         map[string]JVMFlag `json:"flags"`
}

type JVMFlag struct {
	Type  string `json:"type"`
	Value string `json:"value"`
}
