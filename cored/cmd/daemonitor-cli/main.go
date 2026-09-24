package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/charmbracelet/colorprofile"
	"github.com/mattn/go-isatty"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/bootstrap"
	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/render"
	"github.com/cdsap/daemonitor/cored/internal/store"
	"github.com/cdsap/daemonitor/cored/internal/tui"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	fs := flag.NewFlagSet("daemonitor-cli", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)

	socket := fs.String("socket", store.DefaultSocketPath(), "Unix domain socket path")
	pollInterval := fs.Duration("poll-interval", 2*time.Second, "TUI refresh interval")
	noColor := fs.Bool("no-color", false, "Disable color while preserving TUI behavior")
	noAutostart := fs.Bool("no-autostart", false, "Do not start daemonitor-cored automatically")
	jsonOut := fs.Bool("json", false, "Emit machine-readable JSON where supported")
	showHelp := fs.Bool("help", false, "Show help")
	showVersion := fs.Bool("version", false, "Show version")
	// Short aliases
	fs.BoolVar(showHelp, "h", false, "Show help")
	fs.BoolVar(showVersion, "v", false, "Show version")

	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *showHelp {
		printUsage(fs)
		return 0
	}
	if *showVersion {
		fmt.Println(api.Version)
		return 0
	}

	colorEnabled := !*noColor && os.Getenv("NO_COLOR") == ""
	autostart := !*noAutostart
	cmdArgs := fs.Args()
	jsonFlag := *jsonOut
	cmdArgs, jsonFlag = peelJSONFlag(cmdArgs, jsonFlag)
	cmd := ""
	if len(cmdArgs) > 0 {
		cmd = cmdArgs[0]
	}

	switch cmd {
	case "", "top":
		if cmd == "top" && jsonFlag {
			fmt.Fprintln(os.Stderr, "daemonitor-cli: --json is not valid with top")
			return 2
		}
		if cmd == "top" {
			if !interactiveTerminal() {
				fmt.Fprintln(os.Stderr, topRequiresTTYMessage())
				return 1
			}
			return runTop(*socket, *pollInterval, colorEnabled, autostart)
		}
		// No subcommand: TUI on TTY, otherwise one plain snapshot.
		if interactiveTerminal() {
			return runTop(*socket, *pollInterval, colorEnabled, autostart)
		}
		return runOneShot(*socket, "ps", nil, jsonFlag, autostart)
	case "ps", "history", "builds", "logs", "health":
		return runOneShot(*socket, cmd, cmdArgs[1:], jsonFlag, autostart)
	case "help":
		printUsage(fs)
		return 0
	default:
		fmt.Fprintf(os.Stderr, "daemonitor-cli: unknown command %q\n\n", cmd)
		printUsage(fs)
		return 2
	}
}

func runTop(socket string, pollInterval time.Duration, colorEnabled, autostart bool) int {
	// Best-effort discover/start; the TUI keeps a last-good frame and shows
	// socket + recovery hints when no connection has succeeded yet.
	_ = bootstrap.EnsureCore(bootstrap.Options{Socket: socket, Autostart: autostart})

	c := client.New(socket)
	m := tui.NewModel(tui.Config{
		Fetcher:      c,
		Logs:         c,
		PollInterval: pollInterval,
		SocketPath:   socket,
		ColorEnabled: colorEnabled,
	})
	opts := []tea.ProgramOption{}
	if !colorEnabled {
		opts = append(opts, tea.WithColorProfile(colorprofile.Ascii))
		env := append([]string{}, os.Environ()...)
		env = append(env, "NO_COLOR=1")
		opts = append(opts, tea.WithEnvironment(env))
	}
	p := tea.NewProgram(m, opts...)
	_, err := p.Run()
	if err != nil && !errors.Is(err, tea.ErrInterrupted) {
		fmt.Fprintf(os.Stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	return 0
}

func runOneShot(socket, cmd string, args []string, jsonOut, autostart bool) int {
	if err := bootstrap.EnsureCore(bootstrap.Options{Socket: socket, Autostart: autostart}); err != nil {
		fmt.Fprintf(os.Stderr, "daemonitor-cli: %v\n", err)
		return 1
	}

	c := client.New(socket)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	switch cmd {
	case "ps":
		snap, err := c.Processes(ctx)
		if err != nil {
			return failConn(socket, err)
		}
		if jsonOut {
			if err := render.JSON(os.Stdout, snap); err != nil {
				return fail(err)
			}
			return 0
		}
		if err := render.ProcessesPlain(os.Stdout, snap); err != nil {
			return fail(err)
		}
		return 0

	case "history":
		sinceMs := time.Now().Add(-15 * time.Minute).UnixMilli()
		hist, err := c.History(ctx, sinceMs, 200)
		if err != nil {
			return failConn(socket, err)
		}
		if jsonOut {
			if err := render.JSON(os.Stdout, hist); err != nil {
				return fail(err)
			}
			return 0
		}
		if err := render.HistoryPlain(os.Stdout, hist); err != nil {
			return fail(err)
		}
		return 0

	case "health":
		h, err := c.Health(ctx)
		if err != nil {
			return failConn(socket, err)
		}
		if jsonOut {
			if err := render.JSON(os.Stdout, h); err != nil {
				return fail(err)
			}
			return 0
		}
		if err := render.HealthPlain(os.Stdout, h); err != nil {
			return fail(err)
		}
		return 0

	case "logs":
		if len(args) >= 1 {
			pid := args[0]
			if _, err := strconv.ParseInt(pid, 10, 64); err != nil {
				fmt.Fprintf(os.Stderr, "daemonitor-cli: invalid pid %q\n", pid)
				return 2
			}
			var tail render.LogTail
			if err := c.GetJSON(ctx, "/v1/daemon-logs/"+pid+"/tail", &tail); err != nil {
				return failConn(socket, err)
			}
			if jsonOut {
				if err := render.JSON(os.Stdout, tail); err != nil {
					return fail(err)
				}
				return 0
			}
			if err := render.LogTailPlain(os.Stdout, tail); err != nil {
				return fail(err)
			}
			return 0
		}
		var payload render.DaemonLogsPayload
		if err := c.GetJSON(ctx, "/v1/daemon-logs", &payload); err != nil {
			return failConn(socket, err)
		}
		if jsonOut {
			if err := render.JSON(os.Stdout, payload); err != nil {
				return fail(err)
			}
			return 0
		}
		if err := render.DaemonLogsPlain(os.Stdout, payload); err != nil {
			return fail(err)
		}
		return 0

	case "builds":
		var payload render.BuildsPayload
		if err := c.GetJSON(ctx, "/v1/builds", &payload); err != nil {
			return failConn(socket, err)
		}
		if jsonOut {
			if err := render.JSON(os.Stdout, payload); err != nil {
				return fail(err)
			}
			return 0
		}
		if err := render.BuildsPlain(os.Stdout, payload); err != nil {
			return fail(err)
		}
		return 0

	default:
		fmt.Fprintf(os.Stderr, "daemonitor-cli: unknown command %q\n", cmd)
		return 2
	}
}

func failConn(socket string, err error) int {
	fmt.Fprintf(os.Stderr, "daemonitor-cli: cannot connect to daemonitor-cored at %s: %v\nStart daemonitor-cored or check --socket.\n", socket, err)
	return 1
}

func fail(err error) int {
	fmt.Fprintf(os.Stderr, "daemonitor-cli: %v\n", err)
	return 1
}

func interactiveTerminal() bool {
	if strings.EqualFold(os.Getenv("TERM"), "dumb") {
		return false
	}
	return isatty.IsTerminal(os.Stdout.Fd()) && isatty.IsTerminal(os.Stdin.Fd())
}

func topRequiresTTYMessage() string {
	return "daemonitor-cli: top requires an interactive TTY.\nUse: daemonitor-cli ps\n  or: daemonitor-cli ps --json"
}

func peelJSONFlag(args []string, already bool) ([]string, bool) {
	out := make([]string, 0, len(args))
	for _, a := range args {
		if a == "--json" {
			already = true
			continue
		}
		out = append(out, a)
	}
	return out, already
}

func printUsage(fs *flag.FlagSet) {
	fmt.Fprintf(os.Stderr, `Usage: daemonitor-cli [command] [flags]

Commands:
  (default)    Open the TUI on a TTY; otherwise print one plain process snapshot
  top          Open the interactive process monitor (TTY required)
  ps           Print one process snapshot and exit
  history      Print recent process history
  builds       Print recent builds
  logs         List discovered Gradle daemon logs
  logs <pid>   Show the retained log tail for one daemon
  health       Show core health and connection information

Flags:
`)
	fs.PrintDefaults()
}
