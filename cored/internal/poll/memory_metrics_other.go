//go:build !linux && !windows

package poll

// On macOS platform.New().CollectProcess already collects footprint metrics.
func collectPlatformMemory(pid int32, sampleProportional bool, sampledAt int64) (platformMemory, error) {
	return platformMemory{}, nil
}
