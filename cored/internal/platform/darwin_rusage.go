//go:build darwin

package platform

import (
	"sync"
	"unsafe"

	"github.com/ebitengine/purego"
)

const darwinRusageInfoV4 = 4

// darwinRusageInfoV4Struct mirrors macOS <sys/resource.h> struct rusage_info_v4 (296 bytes).
type darwinRusageInfoV4Struct struct {
	UUID                       [16]byte
	UserTime                   uint64
	SystemTime                 uint64
	PkgIdleWkups               uint64
	InterruptWkups             uint64
	Pageins                    uint64
	WiredSize                  uint64
	ResidentSize               uint64
	PhysFootprint              uint64
	ProcStartAbstime           uint64
	ProcExitAbstime            uint64
	ChildUserTime              uint64
	ChildSystemTime            uint64
	ChildPkgIdleWkups          uint64
	ChildInterruptWkups        uint64
	ChildPageins               uint64
	ChildElapsedAbstime        uint64
	DiskIOBytesRead            uint64
	DiskIOBytesWritten         uint64
	CPUTimeQoSDefault          uint64
	CPUTimeQoSMaintenance      uint64
	CPUTimeQoSBackground       uint64
	CPUTimeQoSUtility          uint64
	CPUTimeQoSLegacy           uint64
	CPUTimeQoSUserInitiated    uint64
	CPUTimeQoSUserInteractive  uint64
	BilledSystemTime           uint64
	ServicedSystemTime         uint64
	LogicalWrites              uint64
	LifetimeMaxPhysFootprint   uint64
	Instructions               uint64
	Cycles                     uint64
	BilledEnergy               uint64
	ServicedEnergy             uint64
	IntervalMaxPhysFootprint   uint64
	RunnableTime               uint64
}

var (
	darwinRusageOnce  sync.Once
	darwinRusageFn    func(pid int32, flavor int32, buffer unsafe.Pointer) int32
	darwinRusageErr   error
)

func loadDarwinRusage() error {
	darwinRusageOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libSystem.B.dylib", purego.RTLD_NOW|purego.RTLD_GLOBAL)
		if err != nil {
			darwinRusageErr = err
			return
		}
		purego.RegisterLibFunc(&darwinRusageFn, lib, "proc_pid_rusage")
	})
	return darwinRusageErr
}

type darwinRusageMetrics struct {
	diskRead      IntMetric
	diskWrite     IntMetric
	physFootprint IntMetric
	peakResident  IntMetric
}

func collectDarwinRusage(pid int32, sampledAtMs int64) darwinRusageMetrics {
	if err := loadDarwinRusage(); err != nil || darwinRusageFn == nil {
		u := IntUnavailable(ToolUnavailable, "proc_pid_rusage unavailable")
		return darwinRusageMetrics{diskRead: u, diskWrite: u, physFootprint: u, peakResident: u}
	}
	var usage darwinRusageInfoV4Struct
	if darwinRusageFn(pid, darwinRusageInfoV4, unsafe.Pointer(&usage)) != 0 {
		u := IntUnavailable(ProbeFailed, "proc_pid_rusage")
		return darwinRusageMetrics{diskRead: u, diskWrite: u, physFootprint: u, peakResident: u}
	}
	return darwinRusageMetrics{
		diskRead:      IntAvailable(int64(usage.DiskIOBytesRead), sampledAtMs, "proc_pid_rusage"),
		diskWrite:     IntAvailable(int64(usage.DiskIOBytesWritten), sampledAtMs, "proc_pid_rusage"),
		physFootprint: IntAvailable(int64(usage.PhysFootprint), sampledAtMs, "proc_pid_rusage"),
		peakResident:  IntAvailable(int64(usage.LifetimeMaxPhysFootprint), sampledAtMs, "proc_pid_rusage"),
	}
}
