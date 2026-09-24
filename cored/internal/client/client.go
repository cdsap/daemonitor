// Package client talks to daemonitor-cored over its local Unix-socket HTTP API.
package client

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
)

const defaultTimeout = 3 * time.Second

// Client is a thin HTTP client dialed over a Unix domain socket.
type Client struct {
	socket string
	http   *http.Client
}

// New returns a client bound to socketPath.
func New(socketPath string) *Client {
	return NewWithTimeout(socketPath, defaultTimeout)
}

// NewWithTimeout returns a client with a custom request timeout.
func NewWithTimeout(socketPath string, timeout time.Duration) *Client {
	if timeout <= 0 {
		timeout = defaultTimeout
	}
	return &Client{
		socket: socketPath,
		http: &http.Client{
			Transport: &http.Transport{
				DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
					var d net.Dialer
					return d.DialContext(ctx, "unix", socketPath)
				},
			},
			Timeout: timeout,
		},
	}
}

// Socket returns the configured socket path.
func (c *Client) Socket() string {
	return c.socket
}

// Health calls GET /v1/health.
func (c *Client) Health(ctx context.Context) (model.Health, error) {
	var h model.Health
	err := c.getJSON(ctx, "/v1/health", &h)
	return h, err
}

// Processes calls GET /v1/processes.
func (c *Client) Processes(ctx context.Context) (model.Snapshot, error) {
	var snap model.Snapshot
	err := c.getJSON(ctx, "/v1/processes", &snap)
	return snap, err
}

// History calls GET /v1/processes/history.
func (c *Client) History(ctx context.Context, sinceMs int64, limit int) (model.History, error) {
	path := "/v1/processes/history?since_ms=" + strconv.FormatInt(sinceMs, 10) +
		"&limit=" + strconv.Itoa(limit)
	var hist model.History
	err := c.getJSON(ctx, path, &hist)
	return hist, err
}

// GetJSON performs GET path and decodes a JSON body into dest.
func (c *Client) GetJSON(ctx context.Context, path string, dest any) error {
	return c.getJSON(ctx, path, dest)
}

func (c *Client) getJSON(ctx context.Context, path string, dest any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://daemonitor"+path, nil)
	if err != nil {
		return err
	}
	resp, err := c.http.Do(req)
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
