//go:build windows

package ipc

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestNamedPipeListenDialHTTP(t *testing.T) {
	endpoint := fmt.Sprintf(`\\.\pipe\daemonitor-core-test-%d`, time.Now().UnixNano())
	ln, err := Default.Listen(endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"status":"ok"}`)
	})
	srv := &http.Server{Handler: mux}
	defer srv.Close()
	go func() { _ = srv.Serve(ln) }()

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				return Default.Dial(ctx, endpoint)
			},
		},
		Timeout: 3 * time.Second,
	}

	var last error
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := client.Get("http://daemonitor/v1/health")
		if err == nil {
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("status=%d", resp.StatusCode)
			}
			body, _ := io.ReadAll(resp.Body)
			if string(body) != `{"status":"ok"}` {
				t.Fatalf("body=%s", body)
			}
			return
		}
		last = err
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("health timed out: %v", last)
}

func TestCurrentUserPipeSDDL(t *testing.T) {
	sddl, err := currentUserPipeSDDL()
	if err != nil {
		t.Fatal(err)
	}
	if sddl[:4] != "D:P(" {
		t.Fatalf("sddl=%q", sddl)
	}
}
