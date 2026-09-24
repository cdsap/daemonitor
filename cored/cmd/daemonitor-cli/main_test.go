package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
)

func TestPeelJSONFlag(t *testing.T) {
	args, jsonOut := peelJSONFlag([]string{"ps", "--json"}, false)
	if !jsonOut || len(args) != 1 || args[0] != "ps" {
		t.Fatalf("args=%v json=%v", args, jsonOut)
	}
}

func TestTopRequiresTTYMessage(t *testing.T) {
	msg := topRequiresTTYMessage()
	if !strings.Contains(msg, "ps") || !strings.Contains(msg, "ps --json") {
		t.Fatalf("message should recommend ps / ps --json: %s", msg)
	}
}

func TestPsJSONAgainstFakeCore(t *testing.T) {
	dir := t.TempDir()
	sock := filepath.Join(dir, "test.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(model.Health{Status: "ok", Version: "test", Socket: sock})
	})
	mux.HandleFunc("/v1/processes", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(model.Snapshot{
			SampledAtMs: 42,
			Processes:   []model.Process{{PID: 9, Type: "GRADLE_DAEMON", Name: "d", RSSMemoryMB: 128, Status: "running"}},
		})
	})
	srv := &http.Server{Handler: mux}
	go func() { _ = srv.Serve(ln) }()
	defer srv.Shutdown(context.Background())

	// Wait briefly for listener.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if c, err := net.Dial("unix", sock); err == nil {
			_ = c.Close()
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	oldStdout := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = w
	code := run([]string{"--socket", sock, "--no-autostart", "ps", "--json"})
	_ = w.Close()
	os.Stdout = oldStdout
	out, _ := io.ReadAll(r)
	if code != 0 {
		t.Fatalf("exit=%d out=%s", code, out)
	}
	if render.ContainsANSI(string(out)) {
		t.Fatalf("ANSI in redirected json: %q", out)
	}
	var snap model.Snapshot
	if err := json.Unmarshal(out, &snap); err != nil {
		t.Fatalf("json: %v\n%s", err, out)
	}
	if snap.SampledAtMs != 42 || len(snap.Processes) != 1 || snap.Processes[0].PID != 9 {
		t.Fatalf("unexpected snapshot: %+v", snap)
	}
}

func TestPsPlainAgainstFakeCore(t *testing.T) {
	dir := t.TempDir()
	sock := filepath.Join(dir, "test.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(model.Health{Status: "ok", Version: "test", Socket: sock})
	})
	mux.HandleFunc("/v1/processes", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(model.Snapshot{
			SampledAtMs: 7,
			Processes:   []model.Process{{PID: 3, Type: "KOTLIN_DAEMON", Name: "kd", RSSMemoryMB: 64}},
		})
	})
	srv := &http.Server{Handler: mux}
	go func() { _ = srv.Serve(ln) }()
	defer srv.Shutdown(context.Background())

	oldStdout := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = w
	code := run([]string{"--socket", sock, "--no-autostart", "ps"})
	_ = w.Close()
	os.Stdout = oldStdout
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	if code != 0 {
		t.Fatalf("exit=%d out=%s", code, buf.String())
	}
	out := buf.String()
	if render.ContainsANSI(out) {
		t.Fatalf("ANSI in redirected plain: %q", out)
	}
	if !strings.Contains(out, "pid=3") {
		t.Fatalf("unexpected: %s", out)
	}
}

func TestConnectionFailureBeforeSuccess(t *testing.T) {
	sock := filepath.Join(t.TempDir(), "missing.sock")
	oldStderr := os.Stderr
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stderr = w
	code := run([]string{"--socket", sock, "--no-autostart", "ps"})
	_ = w.Close()
	os.Stderr = oldStderr
	errOut, _ := io.ReadAll(r)
	if code == 0 {
		t.Fatal("expected nonzero exit")
	}
	msg := string(errOut)
	if !strings.Contains(msg, sock) {
		t.Fatalf("expected socket in error: %s", msg)
	}
	if !strings.Contains(msg, "Start daemonitor-cored") && !strings.Contains(msg, "--no-autostart") {
		t.Fatalf("expected recovery hint: %s", msg)
	}
}
