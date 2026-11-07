//go:build linux

package platform

import (
	"bufio"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	procFS   = "/proc"
	cgroupFS = "/sys/fs/cgroup"
)

func collectLinuxCgroup(pid int32) ResourceMetrics {
	result := unavailableResources(ScopeLinuxCgroup, Unsupported, "cgroup_v2")
	path, ok := cgroupPath(pid)
	if !ok {
		return result
	}
	result = unavailableResources(ScopeLinuxCgroup, NotCollected, "cgroup_v2")
	now := time.Now().UnixMilli()
	if value, ok := readCgroupFile(path, "cpu.max"); ok {
		fields := strings.Fields(value)
		if len(fields) == 2 && fields[0] != "max" {
			quota, qerr := strconv.ParseFloat(fields[0], 64)
			period, perr := strconv.ParseFloat(fields[1], 64)
			if qerr == nil && perr == nil && period > 0 {
				result.CPUQuotaCores = RealAvailable(quota/period, now, "cgroup_v2")
			} else {
				result.CPUQuotaCores = RealUnavailable(ProbeFailed, "cgroup_v2")
			}
		} else {
			result.CPUQuotaCores = RealUnavailable(NotCollected, "cgroup_v2")
		}
	}
	stat := readKeyValues(path, "cpu.stat")
	result.CPUUsageSecondsTotal = microsMetric(stat["usage_usec"], now)
	result.CPUThrottledSecondsTotal = microsMetric(stat["throttled_usec"], now)
	result.MemoryCurrentBytes = fileIntMetric(path, "memory.current", now)
	if value, ok := readCgroupFile(path, "memory.max"); ok && value != "max" {
		result.MemoryLimitBytes = parseIntMetric(value, now, "cgroup_v2")
	} else {
		result.MemoryLimitBytes = IntUnavailable(NotCollected, "cgroup_v2")
	}
	events := readKeyValues(path, "memory.events")
	result.MemoryOOMTotal = parseKeyIntMetric(events, "oom", now)
	result.MemoryOOMKillTotal = parseKeyIntMetric(events, "oom_kill", now)
	return result
}

func cgroupPath(pid int32) (string, bool) {
	file, err := os.Open(filepath.Join(procFS, strconv.FormatInt(int64(pid), 10), "cgroup"))
	if err != nil {
		return "", false
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		parts := strings.SplitN(scanner.Text(), ":", 3)
		if len(parts) != 3 || parts[0] != "0" || parts[1] != "" {
			continue
		}
		rel := filepath.Clean(parts[2])
		candidate := filepath.Join(cgroupFS, rel)
		if candidate == cgroupFS || strings.HasPrefix(candidate, cgroupFS+string(os.PathSeparator)) {
			return candidate, true
		}
	}
	return "", false
}

func readCgroupFile(dir, name string) (string, bool) {
	data, err := os.ReadFile(filepath.Join(dir, name))
	return strings.TrimSpace(string(data)), err == nil
}

func readKeyValues(dir, name string) map[string]string {
	values := map[string]string{}
	data, ok := readCgroupFile(dir, name)
	if !ok {
		return values
	}
	for _, line := range strings.Split(data, "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			values[fields[0]] = fields[1]
		}
	}
	return values
}

func parseIntMetric(value string, now int64, source string) IntMetric {
	n, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil {
		return IntUnavailable(ProbeFailed, source)
	}
	return IntAvailable(n, now, source)
}

func fileIntMetric(dir, name string, now int64) IntMetric {
	value, ok := readCgroupFile(dir, name)
	if !ok {
		return IntUnavailable(NotCollected, "cgroup_v2")
	}
	return parseIntMetric(value, now, "cgroup_v2")
}

func parseKeyIntMetric(values map[string]string, key string, now int64) IntMetric {
	value, ok := values[key]
	if !ok {
		return IntUnavailable(NotCollected, "cgroup_v2")
	}
	return parseIntMetric(value, now, "cgroup_v2")
}

func microsMetric(value string, now int64) RealMetric {
	if value == "" {
		return RealUnavailable(NotCollected, "cgroup_v2")
	}
	n, err := strconv.ParseFloat(value, 64)
	if err != nil {
		return RealUnavailable(ProbeFailed, "cgroup_v2")
	}
	return RealAvailable(n/1_000_000, now, "cgroup_v2")
}

func collectLinuxPSI(resource string, now int64) SomePSI {
	file := filepath.Join("/proc/pressure", resource)
	values := map[string]map[string]float64{}
	data, err := os.ReadFile(file)
	if err != nil {
		return unavailablePSI(Unsupported, "procfs")
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		row := map[string]float64{}
		for _, field := range fields[1:] {
			pair := strings.SplitN(field, "=", 2)
			if len(pair) == 2 {
				if v, e := strconv.ParseFloat(pair[1], 64); e == nil {
					row[pair[0]] = v
				}
			}
		}
		values[fields[0]] = row
	}
	metric := func(kind, window string) RealMetric {
		if v, ok := values[kind][window]; ok {
			return RealAvailable(v, now, "procfs")
		}
		return RealUnavailable(NotCollected, "procfs")
	}
	return SomePSI{SomeAvg10: metric("some", "avg10"), SomeAvg60: metric("some", "avg60"), SomeAvg300: metric("some", "avg300"), FullAvg10: metric("full", "avg10"), FullAvg60: metric("full", "avg60"), FullAvg300: metric("full", "avg300")}
}
