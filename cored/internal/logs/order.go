package logs

import "sort"

// Sort orders logs in place: active first, newest modification first, then
// (PID, Tool, Source, Path) ascending. It uses poll-time metadata so unchanged
// watcher state gives the same list and limit prefixes on every request.
func Sort(logs []DaemonLog) {
	sort.Slice(logs, func(i, j int) bool {
		a, b := logs[i], logs[j]
		if a.active != b.active {
			return a.active
		}
		if !a.modified.Equal(b.modified) {
			return a.modified.After(b.modified)
		}
		if a.PID != b.PID {
			return a.PID < b.PID
		}
		if a.Tool != b.Tool {
			return a.Tool < b.Tool
		}
		if a.Source != b.Source {
			return a.Source < b.Source
		}
		return a.Path < b.Path
	})
}
