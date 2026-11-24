// Package bazelprofile ingests the default Bazel JSON trace without enabling BEP.
package bazelprofile

import (
	"compress/gzip"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"sort"
	"strconv"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

const maxProfileBytes = 32 * 1024 * 1024

// readProfile requires a complete gzip stream (including checksum) and complete
// JSON. Both compressed input and decompressed output have hard limits.
func readProfile(path string) (builds.Build, error) {
	f, err := os.Open(path)
	if err != nil {
		return builds.Build{}, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return builds.Build{}, err
	}
	if !info.Mode().IsRegular() || info.Size() > maxProfileBytes {
		return builds.Build{}, errors.New("profile is not a bounded regular file")
	}
	z, err := gzip.NewReader(io.LimitReader(f, maxProfileBytes+1))
	if err != nil {
		return builds.Build{}, err
	}
	defer z.Close()
	data, err := io.ReadAll(io.LimitReader(z, maxProfileBytes+1))
	if err != nil {
		return builds.Build{}, err
	}
	if len(data) > maxProfileBytes {
		return builds.Build{}, errors.New("profile decompressed size limit exceeded")
	}
	return parse(data)
}

type event struct {
	Cat  string                     `json:"cat"`
	Name string                     `json:"name"`
	Ph   string                     `json:"ph"`
	TS   float64                    `json:"ts"`
	Dur  float64                    `json:"dur"`
	Args map[string]json.RawMessage `json:"args"`
}

func parse(data []byte) (builds.Build, error) {
	var p struct {
		Other struct {
			BuildID string      `json:"build_id"`
			Version string      `json:"bazel_version"`
			Date    string      `json:"date"`
			Start   json.Number `json:"profile_start_ts"`
		} `json:"otherData"`
		Events []event `json:"traceEvents"`
	}
	if err := json.Unmarshal(data, &p); err != nil {
		return builds.Build{}, err
	}
	start, _ := strconv.ParseInt(string(p.Other.Start), 10, 64)
	if start <= 0 {
		if date, err := time.Parse(time.RFC3339Nano, p.Other.Date); err == nil {
			start = date.UnixMilli()
		}
	}
	if start <= 0 { // Older profiles used java.util.Date.toString in the host timezone.
		if date, err := time.ParseInLocation("Mon Jan 02 15:04:05 MST 2006", p.Other.Date, time.Local); err == nil {
			start = date.UnixMilli()
		}
	}
	if p.Other.BuildID == "" || start <= 0 || len(p.Events) == 0 {
		return builds.Build{}, errors.New("profile missing build identity or timing")
	}
	m := &builds.BazelProfile{
		BazelVersion: p.Other.Version,
		Date:         p.Other.Date,
		PhaseSeconds: map[string]float64{},
		PeakCounters: map[string]float64{},
	}
	var first, last float64
	var markers []event
	for _, e := range p.Events {
		// Metadata timestamps are not build work. Durations/timestamps are microseconds.
		if e.Ph == "M" {
			continue
		}
		if e.Dur < 0 || math.Abs(e.TS) > 1e15 || e.Dur > 1e15 {
			return builds.Build{}, errors.New("invalid trace timing")
		}
		if e.TS < first {
			first = e.TS
		}
		if e.TS+e.Dur > last {
			last = e.TS + e.Dur
		}
		if e.Ph == "X" && e.Cat == "build phase marker" {
			m.PhaseSeconds[phaseName(e.Name)] += e.Dur / 1e6
		}
		if e.Ph == "i" && e.Cat == "build phase marker" {
			markers = append(markers, e)
		}
		if e.Ph == "X" && e.Cat == "critical path component" {
			m.CriticalPathSeconds += e.Dur / 1e6
			m.CriticalPathComponents++
		}
		if e.Ph == "C" {
			for key, raw := range e.Args {
				var v float64
				if json.Unmarshal(raw, &v) == nil {
					name := e.Name + "/" + key
					if old, ok := m.PeakCounters[name]; !ok || v > old {
						m.PeakCounters[name] = v
					}
				}
			}
		}
	}
	sort.Slice(markers, func(i, j int) bool { return markers[i].TS < markers[j].TS })
	for i, e := range markers {
		end := last
		if i+1 < len(markers) {
			end = markers[i+1].TS
		}
		m.PhaseSeconds[phaseName(e.Name)] += (end - e.TS) / 1e6
	}
	if last <= first {
		return builds.Build{}, errors.New("profile has no elapsed time")
	}
	end := start + int64(last/1000)
	start += int64(first / 1000)
	duration := (last - first) / 1e6
	return builds.Build{
		BuildID:         p.Other.BuildID,
		StartTimeMs:     start,
		EndTimeMs:       &end,
		DurationSeconds: &duration,
		Tool:            builds.ToolBazel,
		InferredSource:  builds.SourceProfile,
		FinalStatus:     builds.StatusCompletedNoOutcome,
		BazelProfile:    m,
	}, nil
}

func phaseName(name string) string {
	switch name {
	case "Load packages", "Loading":
		return "Loading"
	case "Analyze dependencies", "Analysis":
		return "Analysis"
	case "Build artifacts", "Execution":
		return "Execution"
	// Modern Bazel combines loading and analysis (and optionally execution).
	// Keep the combined phase label instead of inventing separate durations.
	default:
		return name
	}
}
