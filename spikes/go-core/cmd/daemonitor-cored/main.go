package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/cdsap/daemonitor/spikes/go-core/internal/api"
)

func main() {
	defaultSock := filepath.Join(os.TempDir(), "daemonitor-core.sock")
	// Default stays under $TMPDIR for isolated spike runs. Same-file dual-run: pass
	// -db matching the app WatcherDatabase path (see store.DefaultWatcherDBPath).
	defaultDB := filepath.Join(os.TempDir(), "daemonitor-core.sqlite")
	socket := flag.String("socket", defaultSock, "Unix domain socket path")
	dbPath := flag.String("db", defaultDB, "SQLite database path (use app watcher.db for same-file open)")
	interval := flag.Duration("interval", 2*time.Second, "process poll interval")
	retention := flag.Duration("retention", 24*time.Hour, "sample retention window")
	gradleHome := flag.String("gradle-user-home", "", "Gradle user home (default: ~/.gradle)")
	flag.Parse()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	srv, err := api.NewServer(*socket, *dbPath, *interval, *retention, *gradleHome)
	if err != nil {
		fmt.Fprintf(os.Stderr, "daemonitor-cored: %v\n", err)
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "daemonitor-cored %s listening on unix://%s db=%s poll=%s retention=%s\n",
		api.Version, *socket, *dbPath, *interval, *retention)
	if err := srv.Run(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "daemonitor-cored: %v\n", err)
		os.Exit(1)
	}
}
