//go:build !linux && !darwin && !windows

package poll

func collectPlatformMemory(pid int32, sampleProportional bool, sampledAt int64) (platformMemory, error) {
	return platformMemory{}, nil
}
