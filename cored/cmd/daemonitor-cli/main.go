package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"github.com/mattn/go-isatty"

	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/store"
	"github.com/cdsap/daemonitor/cored/internal/tui"
)

func main() {
	socket := flag.String("socket", store.DefaultSocketPath(), "Unix domain socket path")
	pollInterval := flag.Duration("poll-interval", 2*time.Second, "TUI refresh interval")
	flag.Parse()

	args := flag.Args()
	cmd := ""
	if len(args) > 0 {
		cmd = args[0]
	}

	switch cmd {
	case "", "top":
		if err := runTop(*socket, *pollInterval); err != nil {
			fmt.Fprintf(os.Stderr, "daemonitor-cli: %v\n", err)
			os.Exit(1)
		}
	case "help", "-h", "--help":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "daemonitor-cli: unknown command %q\n\n", cmd)
		printUsage()
		os.Exit(2)
	}
}

func runTop(socket string, pollInterval time.Duration) error {
	if !interactiveTerminal() {
		fmt.Fprintln(os.Stderr, nonInteractiveMessage())
		return fmt.Errorf("interactive terminal required")
	}

	c := client.New(socket)
	m := tui.NewModel(tui.Config{
		Fetcher:      c,
		PollInterval: pollInterval,
		SocketPath:   socket,
	})
	p := tea.NewProgram(m)
	_, err := p.Run()
	if err != nil && !errors.Is(err, tea.ErrInterrupted) {
		return err
	}
	return nil
}

func interactiveTerminal() bool {
	if strings.EqualFold(os.Getenv("TERM"), "dumb") {
		return false
	}
	return isatty.IsTerminal(os.Stdout.Fd()) && isatty.IsTerminal(os.Stdin.Fd())
}

func nonInteractiveMessage() string {
	return "daemonitor-cli: interactive monitor requires a TTY (TERM is not dumb).\n" +
		"Plain one-shot output lands in a later slice; use daemonitor-corectl processes for now."
}

func printUsage() {
	fmt.Fprintf(os.Stderr, `Usage: daemonitor-cli [top] [flags]

Commands:
  top          Open the interactive process monitor (default on a TTY)

Flags:
  -socket path           Core Unix socket (default: app data dir)
  -poll-interval dur     Refresh interval (default 2s)
`)
}
