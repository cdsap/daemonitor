package observe

import (
	"sync"
	"sync/atomic"
	"time"
)

// Metrics tracks Daemonitor's own collection overhead (not target-build telemetry).
type Metrics struct {
	PollDurationMs     atomic.Int64
	PollOverrunTotal   atomic.Int64
	ProbeDurationMs    atomic.Int64
	ProbeSuccessTotal  atomic.Int64
	ProbeFailureTotal  atomic.Int64
	ProbeTimeoutTotal  atomic.Int64
	StorageWriteMs     atomic.Int64
	StorageSizeBytes   atomic.Int64

	mu            sync.Mutex
	lastPollAtMs  int64
	lastProbeAtMs int64
	lastWriteAtMs int64
}

// Snapshot is a point-in-time view for IPC / CLI.
type Snapshot struct {
	PollDurationMs    int64 `json:"poll_duration_ms"`
	PollOverrunTotal  int64 `json:"poll_overrun_total"`
	ProbeDurationMs   int64 `json:"probe_duration_ms"`
	ProbeSuccessTotal int64 `json:"probe_success_total"`
	ProbeFailureTotal int64 `json:"probe_failure_total"`
	ProbeTimeoutTotal int64 `json:"probe_timeout_total"`
	StorageWriteMs    int64 `json:"storage_write_duration_ms"`
	StorageSizeBytes  int64 `json:"storage_size_bytes"`
	LastPollAtMs      int64 `json:"last_poll_at_ms,omitempty"`
	LastProbeAtMs     int64 `json:"last_probe_at_ms,omitempty"`
	LastWriteAtMs     int64 `json:"last_write_at_ms,omitempty"`
}

func (m *Metrics) ObservePoll(d time.Duration, interval time.Duration) {
	if m == nil {
		return
	}
	ms := d.Milliseconds()
	m.PollDurationMs.Store(ms)
	if interval > 0 && d > interval {
		m.PollOverrunTotal.Add(1)
	}
	m.mu.Lock()
	m.lastPollAtMs = time.Now().UnixMilli()
	m.mu.Unlock()
}

func (m *Metrics) ObserveProbe(d time.Duration, err error, timedOut bool) {
	if m == nil {
		return
	}
	m.ProbeDurationMs.Store(d.Milliseconds())
	if timedOut {
		m.ProbeTimeoutTotal.Add(1)
		m.ProbeFailureTotal.Add(1)
	} else if err != nil {
		m.ProbeFailureTotal.Add(1)
	} else {
		m.ProbeSuccessTotal.Add(1)
	}
	m.mu.Lock()
	m.lastProbeAtMs = time.Now().UnixMilli()
	m.mu.Unlock()
}

func (m *Metrics) ObserveStorageWrite(d time.Duration, sizeBytes int64) {
	if m == nil {
		return
	}
	m.StorageWriteMs.Store(d.Milliseconds())
	if sizeBytes >= 0 {
		m.StorageSizeBytes.Store(sizeBytes)
	}
	m.mu.Lock()
	m.lastWriteAtMs = time.Now().UnixMilli()
	m.mu.Unlock()
}

func (m *Metrics) Snapshot() Snapshot {
	if m == nil {
		return Snapshot{}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return Snapshot{
		PollDurationMs:    m.PollDurationMs.Load(),
		PollOverrunTotal:  m.PollOverrunTotal.Load(),
		ProbeDurationMs:   m.ProbeDurationMs.Load(),
		ProbeSuccessTotal: m.ProbeSuccessTotal.Load(),
		ProbeFailureTotal: m.ProbeFailureTotal.Load(),
		ProbeTimeoutTotal: m.ProbeTimeoutTotal.Load(),
		StorageWriteMs:    m.StorageWriteMs.Load(),
		StorageSizeBytes:  m.StorageSizeBytes.Load(),
		LastPollAtMs:      m.lastPollAtMs,
		LastProbeAtMs:     m.lastProbeAtMs,
		LastWriteAtMs:     m.lastWriteAtMs,
	}
}
