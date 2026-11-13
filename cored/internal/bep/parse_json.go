package bep

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/builds"
)

// partial holds fields accumulated from a BEP stream before finish.
type partial struct {
	BuildID          string
	DaemonPID        int64
	WorkingDirectory string
	ProjectPath      string
	StartTimeMs      int64
	Command          string
	EndTimeMs        int64
	Status           builds.FinalStatus
	Finished         bool
}

// ParseJSONStream reads NDJSON BuildEvent lines and returns closed builds.
// Incomplete streams (started without finished) yield nothing — honest empty.
func ParseJSONStream(r io.Reader) ([]builds.Build, error) {
	state := &partial{}
	var closed []builds.Build
	sc := bufio.NewScanner(r)
	// BEP lines can be large (command env dumps).
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) == 0 {
			continue
		}
		if b, ok := applyJSONEvent(state, line); ok {
			closed = append(closed, b)
			state = &partial{}
		}
	}
	return closed, sc.Err()
}

// ConsumeJSONBytes appends newly available NDJSON bytes into state and returns
// any builds that closed. leftover is incomplete trailing data for the next poll.
func ConsumeJSONBytes(state *partial, pending []byte, chunk []byte) (closed []builds.Build, leftover []byte, err error) {
	if state == nil {
		state = &partial{}
	}
	buf := append(append([]byte{}, pending...), chunk...)
	for {
		nl := bytes.IndexByte(buf, '\n')
		if nl < 0 {
			return closed, buf, nil
		}
		line := bytes.TrimSpace(buf[:nl])
		buf = buf[nl+1:]
		if len(line) == 0 {
			continue
		}
		if b, ok := applyJSONEvent(state, line); ok {
			closed = append(closed, b)
			*state = partial{}
		}
	}
}

func applyJSONEvent(state *partial, line []byte) (builds.Build, bool) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(line, &raw); err != nil {
		return builds.Build{}, false
	}
	if startedRaw, ok := raw["started"]; ok {
		var started struct {
			UUID               string `json:"uuid"`
			StartTimeMillis    any    `json:"startTimeMillis"`
			WorkingDirectory   string `json:"workingDirectory"`
			WorkspaceDirectory string `json:"workspaceDirectory"`
			ServerPid          any    `json:"serverPid"`
			Command            string `json:"command"`
		}
		if err := json.Unmarshal(startedRaw, &started); err != nil {
			return builds.Build{}, false
		}
		state.BuildID = started.UUID
		state.WorkingDirectory = started.WorkingDirectory
		state.ProjectPath = started.WorkspaceDirectory
		state.Command = started.Command
		state.StartTimeMs = anyToInt64(started.StartTimeMillis)
		state.DaemonPID = anyToInt64(started.ServerPid)
		return builds.Build{}, false
	}
	if finishedRaw, ok := raw["finished"]; ok {
		var finished struct {
			FinishTimeMillis any `json:"finishTimeMillis"`
			ExitCode         *struct {
				Name string `json:"name"`
				Code *int   `json:"code"`
			} `json:"exitCode"`
			OverallSuccess *bool `json:"overallSuccess"`
		}
		if err := json.Unmarshal(finishedRaw, &finished); err != nil {
			return builds.Build{}, false
		}
		if state.BuildID == "" {
			return builds.Build{}, false
		}
		state.EndTimeMs = anyToInt64(finished.FinishTimeMillis)
		state.Status = statusFromExit(finished.ExitCode, finished.OverallSuccess)
		state.Finished = true
		return state.toBuild(), true
	}
	return builds.Build{}, false
}

func statusFromExit(exit *struct {
	Name string `json:"name"`
	Code *int   `json:"code"`
}, overall *bool) builds.FinalStatus {
	if exit != nil {
		if exit.Code != nil {
			if *exit.Code == 0 {
				return builds.StatusSuccess
			}
			name := strings.ToUpper(exit.Name)
			if strings.Contains(name, "INTERRUPT") {
				return builds.StatusInterrupted
			}
			return builds.StatusFailed
		}
		switch strings.ToUpper(exit.Name) {
		case "SUCCESS":
			return builds.StatusSuccess
		case "INTERRUPTED":
			return builds.StatusInterrupted
		default:
			if exit.Name != "" {
				return builds.StatusFailed
			}
		}
	}
	if overall != nil {
		if *overall {
			return builds.StatusSuccess
		}
		return builds.StatusFailed
	}
	return builds.StatusCompletedNoOutcome
}

func (p *partial) toBuild() builds.Build {
	b := builds.Build{
		BuildID:          p.BuildID,
		DaemonPID:        p.DaemonPID,
		WorkingDirectory: p.WorkingDirectory,
		ProjectPath:      p.ProjectPath,
		StartTimeMs:      p.StartTimeMs,
		InferredSource:   builds.SourceBEP,
		FinalStatus:      p.Status,
		Tool:             builds.ToolBazel,
	}
	if p.Command != "" {
		cmd := "bazel " + p.Command
		b.CommandLine = &cmd
	}
	if p.EndTimeMs > 0 {
		end := p.EndTimeMs
		b.EndTimeMs = &end
		if p.StartTimeMs > 0 && p.EndTimeMs >= p.StartTimeMs {
			dur := float64(p.EndTimeMs-p.StartTimeMs) / 1000.0
			b.DurationSeconds = &dur
		}
	}
	return b
}

func anyToInt64(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case json.Number:
		n, _ := t.Int64()
		return n
	case string:
		n, _ := strconv.ParseInt(t, 10, 64)
		return n
	case int64:
		return t
	case int:
		return int64(t)
	default:
		return 0
	}
}
