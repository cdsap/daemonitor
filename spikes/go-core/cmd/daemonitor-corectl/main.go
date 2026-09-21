package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/model"
)

func main() {
	defaultSock := filepath.Join(os.TempDir(), "daemonitor-core.sock")
	socket := flag.String("socket", defaultSock, "Unix domain socket path")
	sinceMin := flag.Int("since-min", 15, "history window in minutes (history command)")
	limit := flag.Int("limit", 200, "history row limit")
	flag.Parse()

	args := flag.Args()
	cmd := "processes"
	if len(args) > 0 {
		cmd = args[0]
	}

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", *socket)
			},
		},
		Timeout: 3 * time.Second,
	}

	switch cmd {
	case "health":
		var h model.Health
		if err := getJSON(client, "http://daemonitor/v1/health", &h); err != nil {
			fail(err)
		}
		printJSON(h)
	case "processes", "ps":
		var snap model.Snapshot
		if err := getJSON(client, "http://daemonitor/v1/processes", &snap); err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(snap)
			return
		}
		fmt.Printf("sampled_at_ms=%d processes=%d\n", snap.SampledAtMs, len(snap.Processes))
		for _, p := range snap.Processes {
			fmt.Printf("  pid=%-7d type=%-20s rss=%.0fMB cpu=%.1f%%  %s\n",
				p.PID, p.Type, p.RSSMemoryMB, p.CPUPercent, truncate(p.Name, 40))
		}
	case "history":
		sinceMs := time.Now().Add(-time.Duration(*sinceMin) * time.Minute).UnixMilli()
		url := "http://daemonitor/v1/processes/history?since_ms=" + strconv.FormatInt(sinceMs, 10) +
			"&limit=" + strconv.Itoa(*limit)
		var hist model.History
		if err := getJSON(client, url, &hist); err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(hist)
			return
		}
		fmt.Printf("since_ms=%d count=%d\n", hist.SinceMs, hist.Count)
		for _, p := range hist.Processes {
			fmt.Printf("  ts=%d pid=%-7d type=%-20s rss=%.0fMB\n",
				p.SampledAtMs, p.PID, p.Type, p.RSSMemoryMB)
		}
	default:
		fmt.Fprintf(os.Stderr, "usage: daemonitor-corectl [-socket path] <health|processes|history>\n")
		os.Exit(2)
	}
}

func getJSON(client *http.Client, url string, dest any) error {
	resp, err := client.Get(url)
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

func printJSON(v any) {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(v)
}

func fail(err error) {
	fmt.Fprintf(os.Stderr, "daemonitor-corectl: %v\n", err)
	os.Exit(1)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
