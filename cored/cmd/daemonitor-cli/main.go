package main

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/cdsap/daemonitor/cored/internal/command"
)

func main() {
	opts, err := command.ParseArgs(os.Args[1:])
	if err != nil {
		fmt.Fprintf(os.Stderr, "daemonitor-cli: %v\n", err)
		fmt.Fprint(os.Stderr, command.Usage())
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	os.Exit(command.Run(ctx, opts, os.Stdout, os.Stderr))
}
