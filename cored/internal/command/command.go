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
	"github.com/cdsap/daemonitor/cored/internal/render"
	"github.com/cdsap/daemonitor/cored/internal/store"
	"github.com/cdsap/daemonitor/cored/internal/tui"
	"github.com/mattn/go-isatty"
)

// Version is the CLI package version (shown by -v / --version).
const Version = api.Version

// Options are global CLI flags.
type Options struct {
	Socket       string
	PollInterval time.Duration
	NoColor      bool
	NoAutostart  bool
	JSON         bool
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
	}
	fs.StringVar(&opts.Socket, "socket", opts.Socket, "Unix domain socket path")
	fs.DurationVar(&opts.PollInterval, "poll-interval", opts.PollInterval, "TUI refresh interval")
	fs.BoolVar(&opts.NoColor, "no-color", false, "Disable color")
	fs.BoolVar(&opts.NoAutostart, "no-autostart", false, "Do not start daemonitor-cored automatically")
	fs.BoolVar(&opts.JSON, "json", false, "Emit JSON where supported")
	fs.BoolVar(&opts.Help, "h", false, "Show help")
	fs.BoolVar(&opts.Help, "help", false, "Show help")
	fs.BoolVar(&opts.Version, "v", false, "Show version")
	fs.BoolVar(&opts.Version, "version", false, "Show version")

	if err := fs.Parse(flagArgs); err != nil {
		return opts, err
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
		if opts.JSON {
			_ = render.WriteJSON(stdout, snap)
			return 0
		}
		render.WriteProcessesPlain(stdout, snap)
		return 0
	case "history":
		sinceMs := time.Now().Add(-15 * time.Minute).UnixMilli()
		hist, err := c.History(ctx, sinceMs, 200)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if opts.JSON {
			_ = render.WriteJSON(stdout, hist)
			return 0
		}
		render.WriteHistoryPlain(stdout, hist)
		return 0
	case "builds":
		payload, err := c.Builds(ctx, 100)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if opts.JSON {
			_ = render.WriteJSON(stdout, payload)
			return 0
		}
		render.WriteBuildsPlain(stdout, payload)
		return 0
	case "logs":
		if len(args) > 0 {
			pid, err := strconv.ParseInt(args[0], 10, 64)
			if err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: invalid pid %q\n", args[0])
				return 2
			}
			tail, err := c.DaemonLogTail(ctx, pid)
			if err != nil {
				fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
				return 1
			}
			if opts.JSON {
				_ = render.WriteJSON(stdout, tail)
				return 0
			}
			render.WriteLogTailPlain(stdout, tail)
			return 0
		}
		list, err := c.DaemonLogs(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if opts.JSON {
			_ = render.WriteJSON(stdout, map[string]any{"logs": list})
			return 0
		}
		render.WriteLogsPlain(stdout, list)
		return 0
	case "health":
		h, err := c.Health(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if opts.JSON {
			_ = render.WriteJSON(stdout, h)
			return 0
		}
		render.WriteHealthPlain(stdout, h)
		return 0
	default:
		return 2
	}
}

func isInteractive() bool {
	return isatty.IsTerminal(os.Stdout.Fd()) || isatty.IsCygwinTerminal(os.Stdout.Fd())
}
