//go:build linux

package poll

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

func collectPlatformMemory(pid int32, sampleProportional bool, sampledAt int64) (platformMemory, error) {
	if !sampleProportional {
		return platformMemory{}, nil
	}
	values, err := readSmapsRollup(pid)
	if err != nil {
		return platformMemory{}, err
	}
	return platformMemory{
		proportionalBytes:     values["Pss"],
		proportionalSampledAt: &sampledAt,
		peakResidentBytes:     readProcStatusBytes(pid, "VmHWM"),
	}, nil
}

func readSmapsRollup(pid int32) (map[string]*int64, error) {
	f, err := os.Open(fmt.Sprintf("/proc/%d/smaps_rollup", pid))
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return parseSmapsRollup(f)
}

func readProcStatusBytes(pid int32, metric string) *int64 {
	f, err := os.Open(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return nil
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	prefix := metric + ":"
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, prefix) {
			continue
		}
		parts := strings.Fields(strings.TrimPrefix(line, prefix))
		if len(parts) == 0 || parts[len(parts)-1] != "kB" {
			return nil
		}
		kb, err := strconv.ParseInt(parts[0], 10, 64)
		if err != nil || kb < 0 {
			return nil
		}
		bytes := kb * 1024
		return &bytes
	}
	return nil
}

func parseSmapsRollup(r io.Reader) (map[string]*int64, error) {
	values := make(map[string]*int64)
	scanner := bufio.NewScanner(r)
	for scanner.Scan() {
		key, raw, ok := strings.Cut(scanner.Text(), ":")
		if !ok {
			continue
		}
		parts := strings.Fields(raw)
		if len(parts) == 0 || parts[len(parts)-1] != "kB" {
			continue
		}
		kb, err := strconv.ParseInt(parts[0], 10, 64)
		if err != nil || kb < 0 {
			continue
		}
		bytes := kb * 1024
		values[strings.TrimSpace(key)] = &bytes
	}
	return values, scanner.Err()
}
