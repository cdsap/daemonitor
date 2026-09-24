package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func main() {
	socket := flag.String("socket", store.DefaultSocketPath(), "Unix domain socket path")
	sinceMin := flag.Int("since-min", 15, "history window in minutes (history command)")
	limit := flag.Int("limit", 200, "history row limit")
	flag.Parse()

	args := flag.Args()
	cmd := "processes"
	if len(args) > 0 {
		cmd = args[0]
	}

	c := client.New(*socket)
	ctx := context.Background()

	switch cmd {
	case "health":
		h, err := c.Health(ctx)
		if err != nil {
			fail(err)
		}
		printJSON(h)
	case "processes", "ps":
		snap, err := c.Processes(ctx)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(snap)
			return
		}
		fmt.Printf("sampled_at_ms=%d processes=%d\n", snap.SampledAtMs, len(snap.Processes))
		for _, p := range snap.Processes {
			cpu := "-"
			if p.CPUPercent != nil {
				cpu = fmt.Sprintf("%.1f%%", *p.CPUPercent)
			}
			xmx := "-"
			if p.MaxHeapMB != nil {
				xmx = fmt.Sprintf("%dMB", *p.MaxHeapMB)
			}
			fmt.Printf("  pid=%-7d type=%-20s rss=%dMB cpu=%s xmx=%s  %s\n",
				p.PID, p.Type, p.RSSMemoryMB, cpu, xmx, truncate(p.Name, 40))
		}
	case "history":
		sinceMs := time.Now().Add(-time.Duration(*sinceMin) * time.Minute).UnixMilli()
		hist, err := c.History(ctx, sinceMs, *limit)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(hist)
			return
		}
		fmt.Printf("since_ms=%d count=%d\n", hist.SinceMs, hist.Count)
		for _, p := range hist.Processes {
			fmt.Printf("  ts=%d pid=%-7d type=%-20s rss=%dMB\n",
				p.SampledAtMs, p.PID, p.Type, p.RSSMemoryMB)
		}
	case "logs":
		var payload struct {
			Logs []struct {
				PID           int64  `json:"pid"`
				GradleVersion string `json:"gradle_version"`
				Path          string `json:"path"`
			} `json:"logs"`
		}
		if err := c.GetJSON(ctx, "/v1/daemon-logs", &payload); err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(payload)
			return
		}
		fmt.Printf("daemon_logs=%d\n", len(payload.Logs))
		for _, log := range payload.Logs {
			fmt.Printf("  pid=%-7d gradle=%-8s %s\n", log.PID, log.GradleVersion, log.Path)
		}
	case "log-tail":
		if len(args) < 2 {
			fmt.Fprintf(os.Stderr, "usage: daemonitor-corectl log-tail <pid>\n")
			os.Exit(2)
		}
		pid := args[1]
		var tail struct {
			PID           int64    `json:"pid"`
			GradleVersion string   `json:"gradle_version"`
			Path          string   `json:"path"`
			Lines         []string `json:"lines"`
			Events        []any    `json:"events"`
		}
		if err := c.GetJSON(ctx, "/v1/daemon-logs/"+pid+"/tail", &tail); err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(tail)
			return
		}
		fmt.Printf("pid=%d gradle=%s lines=%d events=%d\n",
			tail.PID, tail.GradleVersion, len(tail.Lines), len(tail.Events))
		for _, line := range tail.Lines {
			fmt.Println(line)
		}
	case "builds":
		var payload struct {
			Count  int `json:"count"`
			Builds []struct {
				BuildID         string   `json:"build_id"`
				DaemonPID       int64    `json:"daemon_pid"`
				FinalStatus     string   `json:"final_status"`
				InferredSource  string   `json:"inferred_source"`
				ProjectPath     string   `json:"project_path"`
				DurationSeconds *float64 `json:"duration_seconds"`
			} `json:"builds"`
		}
		if err := c.GetJSON(ctx, "/v1/builds", &payload); err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			printJSON(payload)
			return
		}
		fmt.Printf("builds=%d\n", payload.Count)
		for _, b := range payload.Builds {
			dur := "-"
			if b.DurationSeconds != nil {
				dur = fmt.Sprintf("%.1fs", *b.DurationSeconds)
			}
			fmt.Printf("  id=%-36s pid=%-7d status=%-20s source=%-8s dur=%s  %s\n",
				truncate(b.BuildID, 36), b.DaemonPID, b.FinalStatus, b.InferredSource, dur, truncate(b.ProjectPath, 40))
		}
	default:
		fmt.Fprintf(os.Stderr, "usage: daemonitor-corectl [-socket path] <health|processes|history|logs|log-tail|builds>\n")
		os.Exit(2)
	}
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
