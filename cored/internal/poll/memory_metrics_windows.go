//go:build windows

package poll

import (
	"fmt"
	"golang.org/x/sys/windows"
	"unsafe"
)

var getProcessMemoryInfo = windows.NewLazySystemDLL("psapi.dll").NewProc("GetProcessMemoryInfo")

type processMemoryCountersEx struct {
	cb                         uint32
	PageFaultCount             uint32
	PeakWorkingSetSize         uintptr
	WorkingSetSize             uintptr
	QuotaPeakPagedPoolUsage    uintptr
	QuotaPagedPoolUsage        uintptr
	QuotaPeakNonPagedPoolUsage uintptr
	QuotaNonPagedPoolUsage     uintptr
	PagefileUsage              uintptr
	PeakPagefileUsage          uintptr
	PrivateUsage               uintptr
}

func int64Ptr(v int64) *int64 { return &v }

func collectPlatformMemory(pid int32, sampleProportional bool, sampledAt int64) (platformMemory, error) {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(pid))
	if err != nil {
		return platformMemory{}, err
	}
	defer windows.CloseHandle(h)
	counters := processMemoryCountersEx{cb: uint32(unsafe.Sizeof(processMemoryCountersEx{}))}
	ret, _, callErr := getProcessMemoryInfo.Call(uintptr(h), uintptr(unsafe.Pointer(&counters)), uintptr(counters.cb))
	if ret == 0 {
		return platformMemory{}, fmt.Errorf("GetProcessMemoryInfo: %w", callErr)
	}
	return platformMemory{
		privateCommittedBytes: int64Ptr(int64(counters.PrivateUsage)),
		peakResidentBytes:     int64Ptr(int64(counters.PeakWorkingSetSize)),
	}, nil
}
