//go:build windows

package platform

import (
	"syscall"
	"unsafe"
)

var kernel32 = syscall.NewLazyDLL("kernel32.dll")
var globalMemoryStatusEx = kernel32.NewProc("GlobalMemoryStatusEx")

type memoryStatusEx struct {
	Length               uint32
	MemoryLoad           uint32
	TotalPhys            uint64
	AvailPhys            uint64
	TotalPageFile        uint64
	AvailPageFile        uint64
	TotalVirtual         uint64
	AvailVirtual         uint64
	AvailExtendedVirtual uint64
}

// collectWindowsCommit uses a read-only process-independent Windows API.
// Job Object membership is deliberately not changed by this collector.
func collectWindowsCommit(now int64) (IntMetric, IntMetric) {
	status := memoryStatusEx{Length: uint32(unsafe.Sizeof(memoryStatusEx{}))}
	if r, _, _ := globalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&status))); r == 0 {
		return IntUnavailable(ProbeFailed, "GlobalMemoryStatusEx"), IntUnavailable(ProbeFailed, "GlobalMemoryStatusEx")
	}
	used := status.TotalPageFile - status.AvailPageFile
	return IntAvailable(int64(used), now, "GlobalMemoryStatusEx"), IntAvailable(int64(status.TotalPageFile), now, "GlobalMemoryStatusEx")
}
