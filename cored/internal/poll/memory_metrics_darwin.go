//go:build darwin && cgo

package poll

/*
#include <libproc.h>
#include <stdlib.h>
static int daemonitor_proc_pid_rusage(int pid, void *buffer) {
    return proc_pid_rusage(pid, RUSAGE_INFO_V4, (rusage_info_t)buffer);
}
*/
import "C"

import (
	"fmt"
	"unsafe"
)

func collectPlatformMemory(pid int32, sampleProportional bool, sampledAt int64) (platformMemory, error) {
	var r C.struct_rusage_info_v4
	if C.daemonitor_proc_pid_rusage(C.int(pid), unsafe.Pointer(&r)) != 0 {
		return platformMemory{}, fmt.Errorf("proc_pid_rusage failed for pid %d", pid)
	}
	return platformMemory{
		physicalFootprintBytes: int64Ptr(int64(r.ri_phys_footprint)),
		peakResidentBytes:      int64Ptr(int64(r.ri_lifetime_max_phys_footprint)),
	}, nil
}

func int64Ptr(v int64) *int64 { return &v }
