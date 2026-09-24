// Command daemonitor-heapprobe: spike helper to probe live heap for a PID via jcmd/jstat.
//
//	go run ./cmd/daemonitor-heapprobe <pid>
package main

import (
	"context"
	"fmt"
	"os"
	"strconv"

	"github.com/cdsap/daemonitor/cored/internal/heap"
)

func main() {
	if len(os.Args) != 2 || os.Args[1] == "-h" || os.Args[1] == "--help" {
		fmt.Fprintf(os.Stderr, "usage: daemonitor-heapprobe <pid>\n")
		os.Exit(2)
	}
	pid64, err := strconv.ParseInt(os.Args[1], 10, 32)
	if err != nil {
		fmt.Fprintf(os.Stderr, "invalid pid: %v\n", err)
		os.Exit(2)
	}
	p := heap.NewProber()
	sample, err := p.SampleFor(context.Background(), int32(pid64), 0)
	if err != nil {
		fmt.Fprintf(os.Stderr, "heapprobe: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("pid=%d source=%s used_mb=%d committed_mb=%d\n",
		pid64, sample.Source, sample.UsedMB, sample.CommittedMB)
}
