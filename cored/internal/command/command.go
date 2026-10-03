package command

import (
	"context"
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
	Socket       string
	PollInterval time.Duration
	NoColor      bool
	NoAutostart  bool
	JSON         bool
	Output       string
	Since        time.Duration
	Limit        int
	PID          int64
	Project      string
	Status       string
	Watch        bool
	Until        time.Duration
	FailOnRSS    int64
	Help         bool
	Version      bool
	Args         []string
}

var knownCommands = map[string]bool{
	"top": true, "ps": true, "history": true, "builds": true, "logs": true, "health": true,
}

var flagsWithValue = map[string]bool{
	"-socket": true, "--socket": true,
	"-poll-interval": true, "--poll-interval": true,
	"-since": true, "--since": true,
	"-limit": true, "--limit": true,
	"-pid": true, "--pid": true,
	"-project": true, "--project": true,
	"-status": true, "--status": true,
	"-until": true, "--until": true,
	"-format": true, "--format": true,
	"-fail-on-rss": true, "--fail-on-rss": true,
}

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
	fs.StringVar(&opts.Socket, "socket", opts.Socket, "Unix domain socket path")
	fs.DurationVar(&opts.PollInterval, "poll-interval", opts.PollInterval, "TUI refresh interval")
	fs.BoolVar(&opts.NoColor, "no-color", false, "Disable color")
	fs.BoolVar(&opts.NoAutostart, "no-autostart", false, "Do not start daemonitor-cored automatically")
	fs.BoolVar(&opts.JSON, "json", false, "Emit JSON where supported")
	fs.StringVar(&opts.Output, "format", opts.Output, "Output format: plain, json, jsonl, or csv")
	fs.DurationVar(&opts.Since, "since", 0, "History window (for example 1h or 15m)")
	fs.IntVar(&opts.Limit, "limit", 0, "Maximum rows to return")
	fs.Int64Var(&opts.PID, "pid", 0, "Filter by process or daemon PID")
	fs.StringVar(&opts.Project, "project", "", "Filter by project path or name")
	fs.StringVar(&opts.Status, "status", "", "Filter builds by final status")
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
	if opts.Limit < 0 || opts.PID < 0 || opts.Since < 0 || opts.Until < 0 || opts.FailOnRSS < 0 || opts.PollInterval <= 0 {
		return opts, fmt.Errorf("limits, durations, pid, and poll interval must be non-negative (poll interval must be positive)")
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

Options:
  --socket PATH            Use an explicit core socket
  --poll-interval DURATION TUI refresh interval (default 2s)
  --no-color               Disable color (NO_COLOR also honored)
  --no-autostart           Do not start daemonitor-cored automatically
  --json                   Emit machine-readable output where supported
  --format FORMAT          Output format: plain, json, jsonl, or csv
  --since DURATION         History window, for example 1h or 15m
  --limit N                Maximum rows to return
  --pid PID                Filter by process or daemon PID
  --project TEXT           Filter by project path or name
  --status TEXT            Filter builds by final status
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
	case "ps", "history", "builds", "logs", "health":
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
		if err := writeProcesses(stdout, snap, opts.Output); err != nil {
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
		payload, err := c.BuildsFiltered(ctx, client.BuildQuery{
			Limit:   limit,
			PID:     opts.PID,
			Project: opts.Project,
			Status:  opts.Status,
		})
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
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
	default:
		return 2
	}
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

func writeProcesses(w io.Writer, snap model.Snapshot, output string) error {
	switch output {
	case "plain":
		render.WriteProcessesPlain(w, snap)
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
					if err := writeProcesses(stdout, snap, opts.Output); err != nil {
						fmt.Fprintln(stderr, err)
					}
					return 3
				}
			}
		}
		if err := writeProcesses(stdout, snap, opts.Output); err != nil {
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
