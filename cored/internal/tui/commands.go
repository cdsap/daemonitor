package tui

import (
	"context"
	"fmt"
	"time"

	tea "charm.land/bubbletea/v2"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/platform"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

type tickMsg time.Time

type snapshotLoadedMsg struct {
	snap model.Snapshot
}

type snapshotFailedMsg struct {
	err error
}

type detailsLoadedMsg struct {
	pid             int64
	startMs         int64
	log             *logs.DaemonLog
	tail            *logs.Tail
	history         []model.Process
	builds          client.BuildsPayload
	buildsErr       error
	host            model.HostMetrics
	hostErr         error
	capabilities    model.Capabilities
	capabilitiesErr error
	err             error
}

type killFailure struct {
	pid int32
	err error
}

type killFinishedMsg struct {
	all       bool
	requested []model.Process
	failures  []killFailure
}

func (m Model) scheduleTick() tea.Cmd {
	return tea.Tick(m.pollInterval, func(t time.Time) tea.Msg {
		return tickMsg(t)
	})
}

func (m Model) fetchSnapshot() tea.Cmd {
	if m.client == nil {
		return func() tea.Msg {
			return snapshotFailedMsg{err: fmt.Errorf("no client")}
		}
	}
	c := m.client
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		snap, err := c.Processes(ctx)
		if err != nil {
			return snapshotFailedMsg{err: err}
		}
		return snapshotLoadedMsg{snap: snap}
	}
}

func (m Model) fetchDetails(p model.Process) tea.Cmd {
	if m.client == nil {
		return nil
	}
	c := m.client
	pid := int64(p.PID)
	startMs := p.StartTimeMs
	gradleDaemon := p.Type == "GRADLE_DAEMON"
	bazelServer := p.Type == "BAZEL_SERVER"
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		var logMeta *logs.DaemonLog
		var builds client.BuildsPayload
		var buildsErr error
		var tail *logs.Tail
		var err error
		if gradleDaemon {
			list, listErr := c.DaemonLogs(ctx)
			if listErr == nil {
				for i := range list {
					if list[i].PID == pid {
						logMeta = &list[i]
						break
					}
				}
			}
			t, tailErr := c.DaemonLogTail(ctx, pid)
			if tailErr != nil {
				err = tailErr
			} else {
				tail = &t
			}
			builds, buildsErr = c.BuildsForDaemon(ctx, pid, startMs, 20)
		}
		if bazelServer {
			// Scope BEP-retained rows to this server incarnation (serverPid + start time).
			builds, buildsErr = c.BuildsFiltered(ctx, client.BuildQuery{Limit: 20, PID: pid, StartTimeMs: startMs, Tool: "BAZEL"})
		}
		history, _ := c.History(ctx, time.Now().Add(-30*time.Minute).UnixMilli(), 500)
		host, hostErr := c.Host(ctx)
		capabilities, capabilitiesErr := c.Capabilities(ctx)
		return detailsLoadedMsg{
			pid: pid, startMs: startMs, log: logMeta, tail: tail, history: history.Processes,
			builds: builds, buildsErr: buildsErr, host: compactHostMetrics(host), hostErr: hostErr,
			capabilities: capabilities, capabilitiesErr: capabilitiesErr, err: err,
		}
	}
}

func (m Model) killProcesses(req killRequest) tea.Cmd {
	terminate := m.terminate
	return func() tea.Msg {
		msg := killFinishedMsg{all: req.all, requested: req.targets}
		for _, p := range req.targets {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			err := terminate(ctx, p.PID, p.StartTimeMs)
			cancel()
			if err != nil {
				msg.failures = append(msg.failures, killFailure{pid: p.PID, err: err})
			}
		}
		return msg
	}
}

func killNotice(msg killFinishedMsg) (string, bool) {
	total := len(msg.requested)
	failed := len(msg.failures)
	if failed == 0 {
		if msg.all {
			return fmt.Sprintf("Sent termination signal to %d processes.", total), false
		}
		p := msg.requested[0]
		return fmt.Sprintf("Sent termination signal to %s pid %d.", render.TypeDisplay(p.Type), p.PID), false
	}
	first := msg.failures[0]
	if !msg.all {
		return fmt.Sprintf("Could not kill pid %d: %v", first.pid, first.err), true
	}
	text := fmt.Sprintf("Sent termination signal to %d of %d processes; pid %d: %v", total-failed, total, first.pid, first.err)
	if failed > 1 {
		text += fmt.Sprintf(" (+%d more failed)", failed-1)
	}
	return text, true
}

func compactHostMetrics(host platform.HostMetrics) model.HostMetrics {
	return model.HostMetrics{
		SampledAtMs:          host.SampledAtMs,
		LogicalProcessors:    model.IntMetric{Availability: string(host.LogicalProcessors.Availability), Value: host.LogicalProcessors.Value, SampledAtMs: host.LogicalProcessors.SampledAtMs},
		CPUUsagePercent:      model.RealMetric{Availability: string(host.CPUUsagePercent.Availability), Value: host.CPUUsagePercent.Value, SampledAtMs: host.CPUUsagePercent.SampledAtMs},
		MemoryTotalBytes:     model.IntMetric{Availability: string(host.MemoryTotalBytes.Availability), Value: host.MemoryTotalBytes.Value, SampledAtMs: host.MemoryTotalBytes.SampledAtMs},
		MemoryAvailableBytes: model.IntMetric{Availability: string(host.MemoryAvailableBytes.Availability), Value: host.MemoryAvailableBytes.Value, SampledAtMs: host.MemoryAvailableBytes.SampledAtMs},
		MemoryUsedBytes:      model.IntMetric{Availability: string(host.MemoryUsedBytes.Availability), Value: host.MemoryUsedBytes.Value, SampledAtMs: host.MemoryUsedBytes.SampledAtMs},
	}
}
