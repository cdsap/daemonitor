//go:build darwin && !cgo

package platform

func collectDarwinDiskIO(pid int32, sampledAtMs int64) (IntMetric, IntMetric) {
	_ = pid
	_ = sampledAtMs
	return IntUnavailable(ToolUnavailable, "proc_pid_rusage requires cgo"),
		IntUnavailable(ToolUnavailable, "proc_pid_rusage requires cgo")
}
