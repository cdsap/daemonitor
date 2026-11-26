//go:build darwin

package poll

// collectPlatformMemory is a no-op on macOS because platform.New().CollectProcess
// already collects ri_phys_footprint and ri_lifetime_max_phys_footprint via
// pure-Go proc_pid_rusage (working identically with CGO_ENABLED=0 and CGO_ENABLED=1).
func collectPlatformMemory(pid int32, sampleProportional bool, sampledAt int64) (platformMemory, error) {
	return platformMemory{}, nil
}
