package store

import (
	"database/sql"
	"sort"

	"github.com/cdsap/daemonitor/cored/internal/builds"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
)

// ResolveBazelServerIncarnation finds the single BAZEL_SERVER incarnation
// whose observed lifetime overlaps [startMs, endMs] for pid. It never merges
// samples across PID reuse.
func (s *Store) ResolveBazelServerIncarnation(hostID string, pid, startMs, endMs int64) (resolvedHost string, serverStartMs int64, ok bool) {
	if pid <= 0 || endMs < startMs {
		return "", 0, false
	}
	// Prefer a BAZEL_SERVER sample directly inside [startMs, endMs].
	err := s.db.QueryRow(`
SELECT host_id, start_time_ms
FROM process_samples
WHERE pid = ? AND process_type = 'BAZEL_SERVER' AND start_time_ms > 0
  AND start_time_ms <= ? AND timestamp >= ? AND timestamp <= ?
  AND (? = '' OR host_id = ?)
ORDER BY start_time_ms DESC, timestamp DESC
LIMIT 1`, pid, endMs, startMs, endMs, hostID, hostID).Scan(&resolvedHost, &serverStartMs)
	if err == nil && serverStartMs > 0 {
		return resolvedHost, serverStartMs, true
	}

	// Fallback to process_instances for builds whose server existed across the window
	// (including sub-poll builds or builds where only descendants were sampled).
	err = s.db.QueryRow(`
SELECT host_id, create_time_ms
FROM process_instances
WHERE pid = ? AND process_type = 'BAZEL_SERVER' AND create_time_ms > 0
  AND create_time_ms <= ? AND last_observed_ms >= ?
  AND (? = '' OR host_id = ?)
ORDER BY create_time_ms DESC
LIMIT 1`, pid, endMs, startMs, hostID, hostID).Scan(&resolvedHost, &serverStartMs)
	if err == nil && serverStartMs > 0 {
		return resolvedHost, serverStartMs, true
	}
	return "", 0, false
}

// BazelBuildWindowSamples computes incarnation-scoped build totals (for
// peak/avg RSS and peak CPU) and per-process-incarnation window deltas
// (matching sessions.SummarizeRSS / #560) over [startMs, endMs] for one
// BAZEL_SERVER incarnation and its owned BAZEL_WORKER / BAZEL_TEST descendants.
func (s *Store) BazelBuildWindowSamples(hostID string, serverPID, serverStartMs, startMs, endMs int64) ([]builds.Sample, []sessions.RSSSummary, error) {
	if serverPID <= 0 || serverStartMs <= 0 || endMs < startMs {
		return nil, nil, nil
	}
	rows, err := s.db.Query(`
SELECT timestamp, host_id, pid, start_time_ms, process_type,
       rss_memory_mb, cpu_percent, subtree_rss_memory_mb, subtree_cpu_percent,
       heap_used_mb, metaspace_used_mb,
       young_gc_count, total_gc_count,
       young_gc_time_ms, old_gc_time_ms
FROM process_samples
WHERE timestamp >= ? AND timestamp <= ?
  AND (? = '' OR host_id = ?)
  AND (
        (pid = ? AND start_time_ms = ?)
     OR (owner_pid = ? AND owner_start_time_ms = ?)
  )
ORDER BY timestamp ASC, pid ASC`,
		startMs, endMs, hostID, hostID,
		serverPID, serverStartMs,
		serverPID, serverStartMs,
	)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()

	type tsBucket struct {
		hasSubtree bool
		subtreeRSS int64
		subtreeCPU *float64

		sumRSS  int64
		sumCPU  float64
		haveCPU bool
	}

	buckets := make(map[int64]*tsBucket)
	timestamps := make([]int64, 0)

	type procKey struct {
		hostID      string
		pid         int64
		startTimeMs int64
		processType string
	}
	byProc := make(map[procKey][]sessions.Sample)
	procKeys := make([]procKey, 0)

	for rows.Next() {
		var ts, pid, startTimeMs, rss int64
		var rowHost, processType string
		var cpu, subtreeCPU sql.NullFloat64
		var subtreeRSS, heap, metaspace, youngCount, totalCount, youngTime, oldTime sql.NullInt64
		if err := rows.Scan(
			&ts, &rowHost, &pid, &startTimeMs, &processType,
			&rss, &cpu, &subtreeRSS, &subtreeCPU,
			&heap, &metaspace,
			&youngCount, &totalCount,
			&youngTime, &oldTime,
		); err != nil {
			return nil, nil, err
		}

		b := buckets[ts]
		if b == nil {
			b = &tsBucket{}
			buckets[ts] = b
			timestamps = append(timestamps, ts)
		}
		isServer := pid == serverPID && startTimeMs == serverStartMs
		if isServer && subtreeRSS.Valid {
			b.hasSubtree = true
			b.subtreeRSS = subtreeRSS.Int64
			if subtreeCPU.Valid {
				v := subtreeCPU.Float64
				b.subtreeCPU = &v
			}
		} else if !b.hasSubtree {
			b.sumRSS += rss
			if cpu.Valid {
				b.sumCPU += cpu.Float64
				b.haveCPU = true
			}
		}

		key := procKey{
			hostID:      rowHost,
			pid:         pid,
			startTimeMs: startTimeMs,
			processType: processType,
		}
		if _, seen := byProc[key]; !seen {
			procKeys = append(procKeys, key)
		}
		byProc[key] = append(byProc[key], sessions.Sample{
			AtMs:            ts,
			RSS:             rss,
			HeapUsedMB:      nullInt64Ptr(heap),
			MetaspaceUsedMB: nullInt64Ptr(metaspace),
			YoungGCCount:    nullInt64Ptr(youngCount),
			TotalGCCount:    nullInt64Ptr(totalCount),
			YoungGCTimeMs:   nullInt64Ptr(youngTime),
			OldGCTimeMs:     nullInt64Ptr(oldTime),
		})
	}
	if err := rows.Err(); err != nil {
		return nil, nil, err
	}
	if len(timestamps) == 0 {
		return nil, nil, nil
	}

	sort.Slice(timestamps, func(i, j int) bool { return timestamps[i] < timestamps[j] })
	buildSamples := make([]builds.Sample, 0, len(timestamps))
	for _, ts := range timestamps {
		b := buckets[ts]
		if b.hasSubtree {
			buildSamples = append(buildSamples, builds.Sample{
				RSSMemoryMB: b.subtreeRSS,
				CPUPercent:  b.subtreeCPU,
			})
			continue
		}
		var cpuPtr *float64
		if b.haveCPU {
			v := b.sumCPU
			cpuPtr = &v
		}
		buildSamples = append(buildSamples, builds.Sample{
			RSSMemoryMB: b.sumRSS,
			CPUPercent:  cpuPtr,
		})
	}

	// Order BAZEL_SERVER first, then descendants deterministically by type, PID, and start time.
	sort.Slice(procKeys, func(i, j int) bool {
		a, b := procKeys[i], procKeys[j]
		aServer := a.pid == serverPID && a.startTimeMs == serverStartMs
		bServer := b.pid == serverPID && b.startTimeMs == serverStartMs
		if aServer != bServer {
			return aServer
		}
		if a.processType != b.processType {
			return a.processType < b.processType
		}
		if a.pid != b.pid {
			return a.pid < b.pid
		}
		return a.startTimeMs < b.startTimeMs
	})

	summaries := make([]sessions.RSSSummary, 0, len(procKeys))
	for _, key := range procKeys {
		ref := sessions.ProcessRef{
			HostID:      key.hostID,
			PID:         key.pid,
			StartTimeMs: key.startTimeMs,
			ProcessType: key.processType,
		}
		if summary, ok := sessions.SummarizeRSS(ref, sessions.AssociationInferred, byProc[key]); ok {
			summaries = append(summaries, summary)
		}
	}
	return buildSamples, summaries, nil
}
