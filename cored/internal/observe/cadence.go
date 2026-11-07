package observe

import "time"

// Cadences are provisional default intervals for independent collectors.
// They are configuration defaults, not validated performance targets.
type Cadences struct {
	ProcessOS      time.Duration
	Host           time.Duration
	JVMHeapGC      time.Duration
	JVMJITCodeCache time.Duration
	LinuxPSS       time.Duration
	ContainerJob   time.Duration
}

// DefaultCadences matches the telemetry expansion spec's suggested starting points.
func DefaultCadences() Cadences {
	return Cadences{
		ProcessOS:       2 * time.Second,
		Host:            5 * time.Second,
		JVMHeapGC:       10 * time.Second,
		JVMJITCodeCache: 20 * time.Second,
		LinuxPSS:        20 * time.Second,
		ContainerJob:    5 * time.Second,
	}
}

// DefaultJVMProbeConcurrency bounds concurrent JVM diagnostic probes.
const DefaultJVMProbeConcurrency = 2
