package command

import (
	"context"
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/bootstrap"
	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/logs"
	"github.com/cdsap/daemonitor/cored/internal/model"
	"github.com/cdsap/daemonitor/cored/internal/render"
	"github.com/cdsap/daemonitor/cored/internal/store"
	"github.com/cdsap/daemonitor/cored/internal/tui"
	"github.com/mattn/go-isatty"
)

// Version is the CLI package version (shown by -v / --version).
var Version = api.Version

// Options are global CLI flags.
type Options struct {
	Socket             string
	PollInterval       time.Duration
	NoColor            bool
	NoAutostart        bool
	JSON               bool
	Details            bool
	Output             string
	OutputPath         string
	Since              time.Duration
	Limit              int
	PID                int64
	Project            string
	Status             string
	IncludeLogs        bool
	IncludeProjectPath bool
	Watch              bool
	Until              time.Duration
	FailOnRSS          int64
	Help               bool
	Version            bool
	Args               []string
}

var knownCommands = map[string]bool{
	"top": true, "ps": true, "history": true, "builds": true, "logs": true, "health": true,
	"metrics": true, "events": true, "host": true, "export": true,
}

var flagsWithValue = map[string]bool{
	"-socket": true, "--socket": true,
	"-poll-interval": true, "--poll-interval": true,
	"-since": true, "--since": true,
	"-limit": true, "--limit": true,
	"-pid": true, "--pid": true,
	"-project": true, "--project": true,
	"-status": true, "--status": true,
	"-include-logs": false, "--include-logs": false,
	"-include-project-path": false, "--include-project-path": false,
	"-until": true, "--until": true,
	"-format": true, "--format": true,
	"-output": true, "--output": true,
	"-fail-on-rss": true, "--fail-on-rss": true,
}

const maxQueryWindow = 7 * 24 * time.Hour

// ParseArgs parses argv into options and remaining command args.
// Flags may appear before or after the subcommand (e.g. `ps --json`).
func ParseArgs(argv []string) (Options, error) {
	var positionals []string
	var flagArgs []string
	for i := 0; i < len(argv); i++ {
		a := argv[i]
		if strings.HasPrefix(a, "-") {
			flagArgs = append(flagArgs, a)
			name, _, hasEq := strings.Cut(a, "=")
			if !hasEq && flagsWithValue[name] && i+1 < len(argv) && !strings.HasPrefix(argv[i+1], "-") {
				i++
				flagArgs = append(flagArgs, argv[i])
			}
			continue
		}
		positionals = append(positionals, a)
	}

	fs := flag.NewFlagSet("daemonitor-cli", flag.ContinueOnError)
	fs.SetOutput(io.Discard)

	opts := Options{
		Socket:       store.DefaultSocketPath(),
		PollInterval: 2 * time.Second,
		Output:       "plain",
		Limit:        0,
	}
	fs.StringVar(&opts.Socket, "socket", opts.Socket, "Local IPC endpoint (Unix socket path or Windows named pipe)")
	fs.DurationVar(&opts.PollInterval, "poll-interval", opts.PollInterval, "TUI refresh interval")
	fs.BoolVar(&opts.NoColor, "no-color", false, "Disable color")
	fs.BoolVar(&opts.NoAutostart, "no-autostart", false, "Do not start daemonitor-cored automatically")
	fs.BoolVar(&opts.JSON, "json", false, "Emit JSON where supported")
	fs.BoolVar(&opts.Details, "details", false, "Show all process metrics in detailed plain output")
	fs.StringVar(&opts.Output, "format", opts.Output, "Output format: plain, json, jsonl, or csv")
	fs.StringVar(&opts.OutputPath, "output", "", "Write export output to PATH")
	fs.DurationVar(&opts.Since, "since", 0, "History window (for example 1h or 15m)")
	fs.IntVar(&opts.Limit, "limit", 0, "Maximum rows to return")
	fs.Int64Var(&opts.PID, "pid", 0, "Filter by process or daemon PID")
	fs.StringVar(&opts.Project, "project", "", "Filter by project path or name")
	fs.StringVar(&opts.Status, "status", "", "Filter builds by final status")
	fs.BoolVar(&opts.IncludeLogs, "include-logs", false, "Include retained build log snippets")
	fs.BoolVar(&opts.IncludeProjectPath, "include-project-path", false, "Include full project paths in exports")
	fs.BoolVar(&opts.Watch, "watch", false, "Continuously refresh a non-interactive ps snapshot")
	fs.DurationVar(&opts.Until, "until", 0, "Stop watch mode after this duration")
	fs.Int64Var(&opts.FailOnRSS, "fail-on-rss", 0, "Exit 3 in watch mode when any process reaches this RSS in MB")
	fs.BoolVar(&opts.Help, "h", false, "Show help")
	fs.BoolVar(&opts.Help, "help", false, "Show help")
	fs.BoolVar(&opts.Version, "v", false, "Show version")
	fs.BoolVar(&opts.Version, "version", false, "Show version")

	if err := fs.Parse(flagArgs); err != nil {
		return opts, err
	}
	if opts.Output != "plain" && opts.Output != "json" && opts.Output != "jsonl" && opts.Output != "csv" {
		return opts, fmt.Errorf("invalid --format %q (want plain, json, jsonl, or csv)", opts.Output)
	}
	if opts.JSON {
		if opts.Output != "plain" && opts.Output != "json" {
			return opts, fmt.Errorf("--json cannot be combined with --format %s", opts.Output)
		}
		opts.Output = "json"
	}
	if opts.Limit < 0 || opts.PID < 0 || opts.Since < 0 || opts.Since > maxQueryWindow || opts.Until < 0 || opts.FailOnRSS < 0 || opts.PollInterval <= 0 {
		return opts, fmt.Errorf("limits, durations, and pid must be non-negative; --since must be at most 168h; poll interval must be positive")
	}
	if len(fs.Args()) > 0 {
		return opts, fmt.Errorf("unexpected arguments: %v", fs.Args())
	}
	opts.Args = positionals
	if os.Getenv("NO_COLOR") != "" {
		opts.NoColor = true
	}
	if len(opts.Args) > 0 && !knownCommands[opts.Args[0]] {
		return opts, fmt.Errorf("unknown command: %s", opts.Args[0])
	}
	return opts, nil
}

// Usage returns the help text.
func Usage() string {
	return strings.TrimSpace(`
Usage: daemonitor-cli [command] [options]

Commands:
  (none)           Open the interactive monitor on a TTY; else print one ps snapshot
  top              Open the interactive monitor (requires a TTY)
  ps               Print one process snapshot and exit
  history          Print recent process history
  builds           Print recent builds
  logs             List discovered Gradle daemon logs
  logs <pid>       Show the retained log tail for one daemon
  health           Show core health and connection information
  metrics list     Show the metric capability/discovery matrix
  metrics          Show bounded process metrics
  events           Show bounded redacted lifecycle events
  host             Show host metrics
  export           Export bounded telemetry as JSONL to --output

Options:
  --socket PATH            Use an explicit core IPC endpoint (Unix socket or Windows named pipe)
  --poll-interval DURATION TUI refresh interval (default 2s)
  --no-color               Disable color (NO_COLOR also honored)
  --no-autostart           Do not start daemonitor-cored automatically
  --json                   Emit machine-readable output where supported
  --details                Show all process metrics for ps in plain output
  --format FORMAT          Output format: plain, json, jsonl, or csv
  --since DURATION         History window, for example 1h or 15m
  --limit N                Maximum rows to return
  --pid PID                Filter by process or daemon PID
  --project TEXT           Filter by project path or name
  --status TEXT            Filter builds by final status
  --include-logs           Include retained build log snippets
  --include-project-path   Include full project paths in exports (default: basename only)
  --watch                  Continuously refresh non-interactive ps output
  --until DURATION         Stop watch mode after this duration
  --fail-on-rss MB         In watch mode, exit 3 when any process reaches this RSS
  -h, --help               Show this help
  -v, --version            Show version
`) + "\n"
}

// Run executes the CLI and returns a process exit code.
func Run(ctx context.Context, opts Options, stdout, stderr io.Writer) int {
	if opts.Help {
		fmt.Fprint(stdout, Usage())
		return 0
	}
	if opts.FailOnRSS > 0 && !opts.Watch {
		fmt.Fprintln(stderr, "daemonitor-cli: --fail-on-rss requires --watch")
		return 2
	}
	if opts.Version {
		fmt.Fprintln(stdout, Version)
		return 0
	}

	cmd := ""
	rest := opts.Args
	if len(rest) > 0 {
		cmd = rest[0]
		rest = rest[1:]
	}

	tty := isInteractive()
	term := os.Getenv("TERM")
	dumb := term == "dumb"

	switch cmd {
	case "", "top":
		if cmd == "top" && (!tty || dumb) {
			fmt.Fprintln(stderr, "daemonitor-cli top requires an interactive terminal; use ps or ps --json")
			return 1
		}
		if cmd == "" && (!tty || dumb) {
			return runOneShot(ctx, opts, "ps", nil, stdout, stderr)
		}
		if opts.JSON {
			fmt.Fprintln(stderr, "--json is not valid with the interactive monitor")
			return 2
		}
		return runTUI(ctx, opts, stderr)
	case "ps", "history", "builds", "logs", "health", "metrics", "events", "host", "export":
		if opts.Watch && cmd != "ps" {
			fmt.Fprintln(stderr, "daemonitor-cli: --watch is only valid with ps")
			return 2
		}
		if opts.Watch {
			return runWatch(ctx, opts, stdout, stderr)
		}
		return runOneShot(ctx, opts, cmd, rest, stdout, stderr)
	default:
		fmt.Fprintf(stderr, "unknown command: %s\n", cmd)
		fmt.Fprint(stderr, Usage())
		return 2
	}
}

func runTUI(ctx context.Context, opts Options, stderr io.Writer) int {
	res, err := bootstrap.Connect(ctx, bootstrap.Options{
		Socket:    opts.Socket,
		Autostart: !opts.NoAutostart,
		Stderr:    os.Stderr,
	})
	if err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	if err := tui.Run(tui.Config{
		Client:       res.Client,
		PollInterval: opts.PollInterval,
		NoColor:      opts.NoColor,
	}); err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	return 0
}

func runOneShot(ctx context.Context, opts Options, cmd string, args []string, stdout, stderr io.Writer) int {
	res, err := bootstrap.Connect(ctx, bootstrap.Options{
		Socket:    opts.Socket,
		Autostart: !opts.NoAutostart,
		Stderr:    os.Stderr,
	})
	if err != nil {
		fmt.Fprintln(stderr, err.Error())
		return 1
	}
	c := res.Client

	switch cmd {
	case "ps":
		snap, err := c.Processes(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		snap.Processes = filterProcesses(snap.Processes, opts)
		render.SanitizeSnapshotForExport(&snap, opts.IncludeProjectPath)
		if err := writeProcesses(stdout, snap, opts.Output, opts.Details); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "history":
		sinceMs := historySince(opts).UnixMilli()
		limit := opts.Limit
		if limit == 0 {
			limit = 200
		}
		hist, err := c.History(ctx, sinceMs, limit)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		hist.Processes = filterProcesses(hist.Processes, opts)
		hist.Count = len(hist.Processes)
		render.SanitizeHistoryForExport(&hist, opts.IncludeProjectPath)
		if err := writeHistory(stdout, hist, opts.Output); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "builds":
		limit := opts.Limit
		if limit == 0 {
			limit = 100
		}
		sinceMs := int64(0)
		if opts.Since > 0 {
			sinceMs = time.Now().Add(-opts.Since).UnixMilli()
		}
		payload, err := c.BuildsFiltered(ctx, client.BuildQuery{
			Limit:       limit,
			SinceMs:     sinceMs,
			PID:         opts.PID,
			Project:     opts.Project,
			Status:      opts.Status,
			IncludeLogs: opts.IncludeLogs,
		})
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		render.SanitizeBuildsForExport(&payload, opts.IncludeProjectPath)
		if err := writeBuilds(stdout, payload, opts.Output); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "logs":
		pid := opts.PID
		if len(args) > 0 {
			var err error
			pid, err = strconv.ParseInt(args[0], 10, 64)
			if err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: invalid pid %q\n", args[0])
				return 2
			}
			tail, err := c.DaemonLogTail(ctx, pid)
			if err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
				return 1
			}
			if err := writeLogTail(stdout, tail, opts.Output); err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
				return 1
			}
			return 0
		}
		list, err := c.DaemonLogs(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		list = filterLogs(list, opts)
		render.SanitizeLogsForExport(&list, opts.IncludeProjectPath)
		if err := writeLogs(stdout, list, opts.Output); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "health":
		h, err := c.Health(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if err := writeValue(stdout, h, opts.Output, func() { render.WriteHealthPlain(stdout, h) }); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "metrics":
		if len(args) > 0 && args[0] == "list" {
			caps, err := c.Capabilities(ctx)
			if err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
				return 1
			}
			if err := writeCapabilities(stdout, caps, opts.Output); err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
				return 1
			}
			return 0
		}
		hist, err := c.History(ctx, historySince(opts).UnixMilli(), boundedLimit(opts.Limit))
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		hist.Processes = filterProcesses(hist.Processes, opts)
		rows := metricRecords(hist.Processes)
		if err := writeMetrics(stdout, rows, opts.Output); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "events":
		events, err := c.Events(ctx, historySince(opts).UnixMilli(), boundedLimit(opts.Limit))
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		events = filterEvents(events, opts.PID)
		if err := writeEvents(stdout, events, opts.Output); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "host":
		host, err := c.Host(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if err := writeValue(stdout, host, opts.Output, func() { render.WriteHostPlain(stdout, host) }); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	case "export":
		if opts.OutputPath == "" {
			fmt.Fprintln(stderr, "daemonitor-cli: export requires --output PATH")
			return 2
		}
		return exportTelemetry(ctx, c, opts, stdout, stderr)
	default:
		return 2
	}
}

func boundedLimit(limit int) int {
	if limit <= 0 || limit > 5000 {
		return 500
	}
	return limit
}

func metricRecords(processes []model.Process) []model.MetricRecord {
	rows := make([]model.MetricRecord, 0, len(processes)*8)
	for _, p := range processes {
		identity := fmt.Sprintf("%d:%d", p.PID, p.StartTimeMs)
		add := func(name, unit string, value any) {
			rows = append(rows, model.MetricRecord{SchemaVersion: 1, MetricName: name, Unit: unit, Value: value,
				Source: "daemonitor-cored/history", CollectionTimeMs: p.SampledAtMs, ProcessIdentity: identity, PID: p.PID})
		}
		add("process.memory.rss_mb", "MB", p.RSSMemoryMB)
		add("process.memory.virtual_mb", "MB", p.VirtualMemoryMB)
		add("process.memory.swap_mb", "MB", p.SwapMemoryMB)
		add("process.threads.count", "count", p.ThreadCount)
		add("process.io.logical_read_bytes_total", "bytes", p.ReadBytes)
		add("process.io.logical_write_bytes_total", "bytes", p.WriteBytes)
		add("process.io.read_operations_total", "count", p.ReadOperations)
		add("process.io.write_operations_total", "count", p.WriteOperations)
		add("process.cpu.usage_percent", "percent", p.CPUPercent)
		add("process.jvm.heap_used_mb", "MB", p.HeapUsedMB)
		add("process.jvm.heap_committed_mb", "MB", p.HeapCommittedMB)
		add("process.jvm.young_gc_count", "count", p.YoungGCCount)
	}
	return rows
}

func writeCapabilities(w io.Writer, caps model.Capabilities, output string) error {
	switch output {
	case "plain":
		for _, m := range caps.Metrics {
			fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", m.MetricName, m.Availability, m.Unit, m.Description)
		}
		return nil
	case "json":
		return render.WriteJSON(w, caps)
	case "jsonl":
		for _, m := range caps.Metrics {
			if err := render.WriteJSONLine(w, m); err != nil {
				return err
			}
		}
		return nil
	case "csv":
		cw := csv.NewWriter(w)
		if err := cw.Write([]string{"metric_name", "availability", "unit", "source", "platform", "description"}); err != nil {
			return err
		}
		for _, m := range caps.Metrics {
			if err := cw.Write([]string{m.MetricName, m.Availability, m.Unit, m.Source, m.Platform, m.Description}); err != nil {
				return err
			}
		}
		cw.Flush()
		return cw.Error()
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}

func writeMetrics(w io.Writer, rows []model.MetricRecord, output string) error {
	switch output {
	case "plain":
		for _, row := range rows {
			fmt.Fprintf(w, "%s=%v %s pid=%d at=%d\n", row.MetricName, row.Value, row.Unit, row.PID, row.CollectionTimeMs)
		}
		return nil
	case "json":
		return render.WriteJSON(w, map[string]any{"schema_version": 1, "metrics": rows})
	case "jsonl":
		for _, row := range rows {
			if err := render.WriteJSONLine(w, row); err != nil {
				return err
			}
		}
		return nil
	case "csv":
		cw := csv.NewWriter(w)
		if err := cw.Write([]string{"schema_version", "metric_name", "unit", "value", "source", "collection_timestamp_ms", "process_identity", "pid"}); err != nil {
			return err
		}
		for _, row := range rows {
			b, _ := json.Marshal(row.Value)
			if err := cw.Write([]string{fmt.Sprint(row.SchemaVersion), row.MetricName, row.Unit, string(b), row.Source, fmt.Sprint(row.CollectionTimeMs), row.ProcessIdentity, fmt.Sprint(row.PID)}); err != nil {
				return err
			}
		}
		cw.Flush()
		return cw.Error()
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}

func filterEvents(events model.Events, pid int64) model.Events {
	if pid <= 0 {
		return events
	}
	filtered := make([]model.ProcessEvent, 0, len(events.Events))
	for _, event := range events.Events {
		if int64(event.PID) == pid {
			filtered = append(filtered, event)
		}
	}
	events.Events = filtered
	events.Count = len(filtered)
	return events
}

func writeEvents(w io.Writer, events model.Events, output string) error {
	switch output {
	case "plain":
		for _, event := range events.Events {
			fmt.Fprintf(w, "%d pid=%d %s\n", event.TimestampMs, event.PID, event.EventType)
		}
		return nil
	case "json":
		return render.WriteJSON(w, events)
	case "jsonl":
		for _, event := range events.Events {
			if err := render.WriteJSONLine(w, event); err != nil {
				return err
			}
		}
		return nil
	case "csv":
		cw := csv.NewWriter(w)
		if err := cw.Write([]string{"schema_version", "event_type", "timestamp_ms", "process_instance_id", "pid", "process_type", "source"}); err != nil {
			return err
		}
		for _, event := range events.Events {
			if err := cw.Write([]string{
				fmt.Sprint(event.SchemaVersion), event.EventType, fmt.Sprint(event.TimestampMs),
				event.ProcessInstanceID, fmt.Sprint(event.PID), event.ProcessType, event.Source,
			}); err != nil {
				return err
			}
		}
		cw.Flush()
		return cw.Error()
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}

func exportTelemetry(ctx context.Context, c *client.Client, opts Options, stdout, stderr io.Writer) int {
	file, err := os.OpenFile(opts.OutputPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	defer file.Close()
	enc := json.NewEncoder(file)
	start := historySince(opts).UnixMilli()
	hist, err := c.History(ctx, start, boundedLimit(opts.Limit))
	if err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	for _, row := range metricRecords(filterProcesses(hist.Processes, opts)) {
		if err := enc.Encode(row); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
	}
	events, err := c.Events(ctx, start, boundedLimit(opts.Limit))
	if err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	for _, row := range filterEvents(events, opts.PID).Events {
		if err := enc.Encode(row); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
	}
	host, err := c.Host(ctx)
	if err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	if err := enc.Encode(map[string]any{"schema_version": 1, "kind": "host", "collection_timestamp_ms": host.SampledAtMs, "source": "daemonitor-cored/host", "host": host}); err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	_ = stdout
	return 0
}

func historySince(opts Options) time.Time {
	window := opts.Since
	if window == 0 {
		window = 15 * time.Minute
	}
	return time.Now().Add(-window)
}

func filterProcesses(items []model.Process, opts Options) []model.Process {
	out := items[:0]
	needle := strings.ToLower(opts.Project)
	for _, p := range items {
		if opts.PID > 0 && int64(p.PID) != opts.PID {
			continue
		}
		if needle != "" && !strings.Contains(strings.ToLower(render.ProjectName(p)), needle) && !strings.Contains(strings.ToLower(p.CommandLine), needle) {
			continue
		}
		out = append(out, p)
	}
	return out
}

func filterLogs(items []logs.DaemonLog, opts Options) []logs.DaemonLog {
	if opts.PID == 0 {
		return items
	}
	out := items[:0]
	for _, item := range items {
		if item.PID == opts.PID {
			out = append(out, item)
		}
	}
	return out
}

func writeValue(w io.Writer, v any, output string, plain func()) error {
	switch output {
	case "plain":
		plain()
		return nil
	case "json":
		return render.WriteJSON(w, v)
	case "jsonl":
		return render.WriteJSON(w, v)
	default:
		return fmt.Errorf("format %s is not supported for this command", output)
	}
}

func writeProcesses(w io.Writer, snap model.Snapshot, output string, details bool) error {
	switch output {
	case "plain":
		if details {
			render.WriteProcessesDetailedPlain(w, snap)
		} else {
			render.WriteProcessesPlain(w, snap)
		}
		return nil
	case "json":
		return render.WriteJSON(w, snap)
	case "jsonl":
		return render.WriteJSONLine(w, snap)
	case "csv":
		return render.WriteProcessesCSV(w, snap)
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}
func writeHistory(w io.Writer, hist model.History, output string) error {
	switch output {
	case "plain":
		render.WriteHistoryPlain(w, hist)
		return nil
	case "json":
		return render.WriteJSON(w, hist)
	case "jsonl":
		return render.WriteJSONLine(w, hist)
	case "csv":
		return render.WriteHistoryCSV(w, hist)
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}
func writeBuilds(w io.Writer, payload client.BuildsPayload, output string) error {
	switch output {
	case "plain":
		render.WriteBuildsPlain(w, payload)
		return nil
	case "json":
		return render.WriteJSON(w, payload)
	case "jsonl":
		return render.WriteJSONLine(w, payload)
	case "csv":
		return render.WriteBuildsCSV(w, payload)
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}
func writeLogs(w io.Writer, list []logs.DaemonLog, output string) error {
	v := map[string]any{"logs": list}
	switch output {
	case "plain":
		render.WriteLogsPlain(w, list)
		return nil
	case "json":
		return render.WriteJSON(w, v)
	case "jsonl":
		return render.WriteJSONLine(w, v)
	case "csv":
		return render.WriteLogsCSV(w, list)
	default:
		return fmt.Errorf("unsupported output format %s", output)
	}
}
func writeLogTail(w io.Writer, tail logs.Tail, output string) error {
	switch output {
	case "plain":
		render.WriteLogTailPlain(w, tail)
		return nil
	case "json":
		return render.WriteJSON(w, tail)
	case "jsonl":
		return render.WriteJSONLine(w, tail)
	default:
		return fmt.Errorf("format %s is not supported for log tails", output)
	}
}

func runWatch(ctx context.Context, opts Options, stdout, stderr io.Writer) int {
	deadline := time.Time{}
	if opts.Until > 0 {
		deadline = time.Now().Add(opts.Until)
	}
	res, err := bootstrap.Connect(ctx, bootstrap.Options{Socket: opts.Socket, Autostart: !opts.NoAutostart, Stderr: os.Stderr})
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	for {
		if !deadline.IsZero() && !time.Now().Before(deadline) {
			return 0
		}
		snap, err := res.Client.Processes(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		snap.Processes = filterProcesses(snap.Processes, opts)
		if opts.FailOnRSS > 0 {
			for _, process := range snap.Processes {
				if process.RSSMemoryMB >= opts.FailOnRSS {
					if err := writeProcesses(stdout, snap, opts.Output, opts.Details); err != nil {
						fmt.Fprintln(stderr, err)
					}
					return 3
				}
			}
		}
		if err := writeProcesses(stdout, snap, opts.Output, opts.Details); err != nil {
			fmt.Fprintln(stderr, err)
			return 1
		}
		if opts.Until == 0 && ctx.Err() != nil {
			return 0
		}
		timer := time.NewTimer(opts.PollInterval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return 0
		case <-timer.C:
		}
	}
}

func isInteractive() bool {
	return isatty.IsTerminal(os.Stdout.Fd()) || isatty.IsCygwinTerminal(os.Stdout.Fd())
}
