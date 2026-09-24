package tui

import (
	"sort"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

// SortField identifies the primary process-table sort column.
type SortField int

const (
	SortRSS SortField = iota
	SortCPU
	SortPID
	SortType
	SortUptime
	SortProject
)

// SortOrder is ascending or descending primary sort.
type SortOrder int

const (
	SortDesc SortOrder = iota
	SortAsc
)

var sortCycle = []SortField{
	SortRSS,
	SortCPU,
	SortPID,
	SortType,
	SortUptime,
	SortProject,
}

// DefaultSortOrder returns the preferred direction for a newly selected field.
func DefaultSortOrder(field SortField) SortOrder {
	switch field {
	case SortPID, SortType, SortProject:
		return SortAsc
	default:
		return SortDesc
	}
}

func (f SortField) String() string {
	switch f {
	case SortRSS:
		return "RSS"
	case SortCPU:
		return "CPU"
	case SortPID:
		return "PID"
	case SortType:
		return "TYPE"
	case SortUptime:
		return "UPTIME"
	case SortProject:
		return "PROJECT"
	default:
		return "?"
	}
}

func (o SortOrder) Arrow() string {
	if o == SortAsc {
		return "↑"
	}
	return "↓"
}

func cycleSortField(current SortField) SortField {
	for i, f := range sortCycle {
		if f == current {
			return sortCycle[(i+1)%len(sortCycle)]
		}
	}
	return SortRSS
}

func toggleSortOrder(current SortOrder) SortOrder {
	if current == SortAsc {
		return SortDesc
	}
	return SortAsc
}

// sortedProcesses orders rows by field/order with PID ascending as the tiebreaker.
func sortedProcesses(in []model.Process, field SortField, order SortOrder, now time.Time) []model.Process {
	out := make([]model.Process, len(in))
	copy(out, in)
	sort.SliceStable(out, func(i, j int) bool {
		cmp := compareProcesses(out[i], out[j], field, now)
		if cmp != 0 {
			if order == SortAsc {
				return cmp < 0
			}
			return cmp > 0
		}
		return out[i].PID < out[j].PID
	})
	return out
}

func compareProcesses(a, b model.Process, field SortField, now time.Time) int {
	switch field {
	case SortRSS:
		return cmpInt64(a.RSSMemoryMB, b.RSSMemoryMB)
	case SortCPU:
		return cmpFloat(cpuValue(a.CPUPercent), cpuValue(b.CPUPercent))
	case SortPID:
		return cmpInt32(a.PID, b.PID)
	case SortType:
		return cmpString(a.Type, b.Type)
	case SortUptime:
		return cmpInt64(uptimeMs(a, now), uptimeMs(b, now))
	case SortProject:
		return cmpString(projectName(a), projectName(b))
	default:
		return cmpInt64(a.RSSMemoryMB, b.RSSMemoryMB)
	}
}

func cpuValue(v *float64) float64 {
	if v == nil {
		return -1
	}
	return *v
}

func uptimeMs(p model.Process, now time.Time) int64 {
	if p.StartTimeMs <= 0 {
		return -1
	}
	ref := now
	if ref.IsZero() && p.SampledAtMs > 0 {
		ref = time.UnixMilli(p.SampledAtMs)
	}
	if ref.IsZero() {
		return -1
	}
	u := ref.UnixMilli() - p.StartTimeMs
	if u < 0 {
		return 0
	}
	return u
}

func cmpInt64(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

func cmpInt32(a, b int32) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

func cmpFloat(a, b float64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}

func cmpString(a, b string) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	default:
		return 0
	}
}
