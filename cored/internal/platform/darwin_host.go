//go:build darwin

package platform

import (
	"sync"
	"unsafe"

	"github.com/ebitengine/purego"
)

const notifyStatusOK = 0

var (
	darwinHostOnce       sync.Once
	darwinSysctlByNameFn func(name string, oldp unsafe.Pointer, oldlenp *uintptr, newp unsafe.Pointer, newlen uintptr) int32
	darwinNotifyCheckFn  func(name string, outToken *int32) uint32
	darwinNotifyStateFn  func(token int32, state *uint64) uint32
	darwinNotifyCancelFn func(token int32) uint32
	darwinHostErr        error
)

func loadDarwinHostSymbols() error {
	darwinHostOnce.Do(func() {
		lib, err := purego.Dlopen("/usr/lib/libSystem.B.dylib", purego.RTLD_NOW|purego.RTLD_GLOBAL)
		if err != nil {
			darwinHostErr = err
			return
		}
		purego.RegisterLibFunc(&darwinSysctlByNameFn, lib, "sysctlbyname")
		purego.RegisterLibFunc(&darwinNotifyCheckFn, lib, "notify_register_check")
		purego.RegisterLibFunc(&darwinNotifyStateFn, lib, "notify_get_state")
		purego.RegisterLibFunc(&darwinNotifyCancelFn, lib, "notify_cancel")
	})
	return darwinHostErr
}

func collectDarwinHostContext(sampledAtMs int64) (pressure StringMetric, thermal StringMetric) {
	if err := loadDarwinHostSymbols(); err != nil {
		return StringUnavailable(ToolUnavailable, "sysctl:kern.memorystatus_vm_pressure_level"),
			StringUnavailable(ToolUnavailable, "notify:thermalpressurelevel")
	}

	var vmLevel int32
	size := unsafe.Sizeof(vmLevel)
	if darwinSysctlByNameFn != nil && darwinSysctlByNameFn("kern.memorystatus_vm_pressure_level", unsafe.Pointer(&vmLevel), &size, nil, 0) == 0 {
		pressure = StringAvailable(mapDarwinMemoryPressure(int(vmLevel)), sampledAtMs, "sysctl:kern.memorystatus_vm_pressure_level")
	} else {
		pressure = StringUnavailable(ProbeFailed, "sysctl:kern.memorystatus_vm_pressure_level")
	}

	var token int32
	var thermalState uint64
	if darwinNotifyCheckFn != nil && darwinNotifyStateFn != nil && darwinNotifyCancelFn != nil &&
		darwinNotifyCheckFn("com.apple.system.thermalpressurelevel", &token) == notifyStatusOK {
		rc := darwinNotifyStateFn(token, &thermalState)
		darwinNotifyCancelFn(token)
		if rc == notifyStatusOK {
			thermal = StringAvailable(mapDarwinThermalState(thermalState), sampledAtMs, "notify:thermalpressurelevel")
		} else {
			thermal = StringUnavailable(ProbeFailed, "notify:thermalpressurelevel")
		}
	} else {
		thermal = StringUnavailable(ProbeFailed, "notify:thermalpressurelevel")
	}
	return pressure, thermal
}

func mapDarwinMemoryPressure(level int) string {
	switch level {
	case 1:
		return "normal"
	case 2:
		return "warn"
	case 4:
		return "critical"
	default:
		return "unknown"
	}
}

func mapDarwinThermalState(level uint64) string {
	switch level {
	case 0:
		return "nominal"
	case 1:
		return "moderate"
	case 2:
		return "heavy"
	case 3:
		return "trapping"
	case 4:
		return "sleeping"
	default:
		return "unknown"
	}
}
