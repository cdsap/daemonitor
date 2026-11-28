//go:build windows

package poll

import (
	"fmt"
	"unsafe"

	"github.com/cdsap/daemonitor/cored/internal/ptr"
	"golang.org/x/sys/windows"
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
		privateCommittedBytes: ptr.Int64(int64(counters.PrivateUsage)),
		peakResidentBytes:     ptr.Int64(int64(counters.PeakWorkingSetSize)),
	}, nil
}
