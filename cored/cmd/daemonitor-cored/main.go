package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/api"
	"github.com/cdsap/daemonitor/cored/internal/ipc"
	"github.com/cdsap/daemonitor/cored/internal/store"
)

func main() {
	defaultSock := store.DefaultSocketPath()
	defaultDB := store.DefaultWatcherDBPath()
	socket := flag.String("socket", defaultSock, "Local IPC endpoint (Unix socket path or Windows named pipe)")
	dbPath := flag.String("db", defaultDB, "SQLite database path (defaults to app watcher.db)")
	interval := flag.Duration("interval", 2*time.Second, "process poll interval")
	heapInterval := flag.Duration("jvm-heap-interval", 10*time.Second, "minimum heap/GC probe cadence (dispatched on process scan ticks)")
	advancedInterval := flag.Duration("jvm-advanced-interval", 20*time.Second, "minimum JIT/class/code-cache/safepoint probe cadence")
	probeBudget := flag.Duration("jvm-probe-budget", 750*time.Millisecond, "timeout budget per JVM family attempt")
	retention := flag.Duration("retention", 15*24*time.Hour, "sample and build retention window")
	gradleHome := flag.String("gradle-user-home", "", "Gradle user home (default: ~/.gradle)")
	flag.Parse()
	if *interval <= 0 || *heapInterval <= 0 || *advancedInterval <= 0 || *probeBudget <= 0 {
		fmt.Fprintln(os.Stderr, "daemonitor-cored: sampling intervals and probe budget must be positive")
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	srv, err := api.NewServer(*socket, *dbPath, *interval, *retention, *gradleHome)
	if err != nil {
		fmt.Fprintf(os.Stderr, "daemonitor-cored: %v\n", err)
		os.Exit(1)
	}
	srv.Collector.Cadences.JVMHeapGC = *heapInterval
	srv.Collector.Cadences.JVMJITCodeCache = *advancedInterval
	srv.Collector.Heap.Timeout = *probeBudget
	fmt.Fprintf(os.Stderr, "daemonitor-cored %s listening on %s://%s db=%s poll=%s retention=%s\n",
		api.Version, ipc.Scheme(*socket), *socket, *dbPath, *interval, *retention)
	if err := srv.Run(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "daemonitor-cored: %v\n", err)
		os.Exit(1)
	}
}
