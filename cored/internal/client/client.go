// Package client is a thin Unix-socket HTTP client for daemonitor-cored.
package client

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
)

const defaultTimeout = 3 * time.Second

// Client talks to daemonitor-cored over a Unix domain socket.
type Client struct {
	Socket string
	HTTP   *http.Client
}

// New returns a client for the given socket path.
func New(socket string) *Client {
	return &Client{
		Socket: socket,
		HTTP: &http.Client{
			Transport: &http.Transport{
				DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, "unix", socket)
				},
			},
			Timeout: defaultTimeout,
		},
	}
}

// Health fetches /v1/health.
func (c *Client) Health(ctx context.Context) (model.Health, error) {
	var h model.Health
	err := c.getJSON(ctx, "/v1/health", &h)
	return h, err
}

// Processes fetches /v1/processes.
func (c *Client) Processes(ctx context.Context) (model.Snapshot, error) {
	var snap model.Snapshot
	err := c.getJSON(ctx, "/v1/processes", &snap)
	return snap, err
}

// History fetches recent process history.
func (c *Client) History(ctx context.Context, sinceMs int64, limit int) (model.History, error) {
	path := "/v1/processes/history?since_ms=" + strconv.FormatInt(sinceMs, 10) +
		"&limit=" + strconv.Itoa(limit)
	var hist model.History
	err := c.getJSON(ctx, path, &hist)
	return hist, err
}

// DaemonLogs lists discovered Gradle daemon logs.
func (c *Client) DaemonLogs(ctx context.Context) ([]logs.DaemonLog, error) {
	var payload struct {
		Logs []logs.DaemonLog `json:"logs"`
	}
	if err := c.getJSON(ctx, "/v1/daemon-logs", &payload); err != nil {
		return nil, err
	}
	return payload.Logs, nil
}

// DaemonLogTail fetches the retained redacted tail for one daemon PID.
func (c *Client) DaemonLogTail(ctx context.Context, pid int64) (logs.Tail, error) {
	var tail logs.Tail
	err := c.getJSON(ctx, "/v1/daemon-logs/"+strconv.FormatInt(pid, 10)+"/tail", &tail)
	return tail, err
}

// BuildsPayload is the /v1/builds response.
type BuildsPayload struct {
	Count  int           `json:"count"`
	Builds []BuildRecord `json:"builds"`
}

// BuildRecord is one build row from the core API.
type BuildRecord struct {
	BuildID         string   `json:"build_id"`
	DaemonPID       int64    `json:"daemon_pid"`
	FinalStatus     string   `json:"final_status"`
	InferredSource  string   `json:"inferred_source"`
	Agent           string   `json:"agent"`
	AgentProvider   string   `json:"agent_provider"`
	ProjectPath     string   `json:"project_path"`
	DurationSeconds *float64 `json:"duration_seconds"`
}

// BuildQuery describes the filters supported by the core build endpoint.
type BuildQuery struct {
	Limit   int
	PID     int64
	Project string
	Status  string
}

// Builds fetches recent builds.
func (c *Client) Builds(ctx context.Context, limit int) (BuildsPayload, error) {
	return c.BuildsFiltered(ctx, BuildQuery{Limit: limit})
}

// BuildsFiltered fetches builds with filters applied by daemonitor-cored.
func (c *Client) BuildsFiltered(ctx context.Context, query BuildQuery) (BuildsPayload, error) {
	params := url.Values{}
	if query.Limit > 0 {
		params.Set("limit", strconv.Itoa(query.Limit))
	}
	if query.PID > 0 {
		params.Set("pid", strconv.FormatInt(query.PID, 10))
	}
	if query.Project != "" {
		params.Set("project", query.Project)
	}
	if query.Status != "" {
		params.Set("status", query.Status)
	}
	path := "/v1/builds"
	if encoded := params.Encode(); encoded != "" {
		path += "?" + encoded
	}
	var payload BuildsPayload
	err := c.getJSON(ctx, path, &payload)
	return payload, err
}

func (c *Client) getJSON(ctx context.Context, path string, dest any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://daemonitor"+path, nil)
	if err != nil {
		return err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, body)
	}
	return json.NewDecoder(resp.Body).Decode(dest)
}
