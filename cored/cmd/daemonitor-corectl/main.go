package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/render"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func main() {
	defaultSock := store.DefaultSocketPath()
	socket := flag.String("socket", defaultSock, "Unix domain socket path")
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
		if os.Getenv("JSON") == "1" {
			_ = render.WriteJSON(os.Stdout, h)
			return
		}
		render.WriteHealthPlain(os.Stdout, h)
	case "processes", "ps":
		snap, err := c.Processes(ctx)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			_ = render.WriteJSON(os.Stdout, snap)
			return
		}
		render.WriteProcessesPlain(os.Stdout, snap)
	case "history":
		sinceMs := time.Now().Add(-time.Duration(*sinceMin) * time.Minute).UnixMilli()
		hist, err := c.History(ctx, sinceMs, *limit)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			_ = render.WriteJSON(os.Stdout, hist)
			return
		}
		render.WriteHistoryPlain(os.Stdout, hist)
	case "logs":
		list, err := c.DaemonLogs(ctx)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			_ = render.WriteJSON(os.Stdout, map[string]any{"logs": list})
			return
		}
		render.WriteLogsPlain(os.Stdout, list)
	case "log-tail":
		if len(args) < 2 {
			fmt.Fprintf(os.Stderr, "usage: daemonitor-corectl log-tail <pid>\n")
			os.Exit(2)
		}
		pid, err := strconv.ParseInt(args[1], 10, 64)
		if err != nil {
			fail(err)
		}
		tail, err := c.DaemonLogTail(ctx, pid)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			_ = render.WriteJSON(os.Stdout, tail)
			return
		}
		render.WriteLogTailPlain(os.Stdout, tail)
	case "builds":
		payload, err := c.Builds(ctx, 100)
		if err != nil {
			fail(err)
		}
		if os.Getenv("JSON") == "1" {
			_ = render.WriteJSON(os.Stdout, payload)
			return
		}
		render.WriteBuildsPlain(os.Stdout, payload)
	default:
		fmt.Fprintf(os.Stderr, "usage: daemonitor-corectl [-socket path] <health|processes|history|logs|log-tail|builds>\n")
		os.Exit(2)
	}
}

func fail(err error) {
	fmt.Fprintf(os.Stderr, "daemonitor-corectl: %v\n", err)
	os.Exit(1)
}
