package poll

import (
	"context"
	"path/filepath"
	"strings"

	"github.com/cdsap/daemonitor/cored/internal/heap"
	"github.com/shirou/gopsutil/v4/process"
)

// Bazel rewrites the server's argv[0], so resolve its embedded runtime from
// --install_base rather than interpreting the displayed executable as a path.
func bazelTargetJDKDir(pid int32) string {
	ctx, cancel := context.WithTimeout(context.Background(), heap.DefaultTimeout)
	defer cancel()
	p, err := process.NewProcessWithContext(ctx, pid)
	if err != nil {
		return ""
	}
	args, err := p.CmdlineSliceWithContext(ctx)
	if err != nil {
		return ""
	}
	return bazelEmbeddedJDKDir(args)
}

func bazelEmbeddedJDKDir(args []string) string {
	if Classify(strings.Join(args, " ")) != "BAZEL_SERVER" {
		return ""
	}
	for _, arg := range args {
		if base, ok := strings.CutPrefix(arg, "--install_base="); ok && base != "" {
			return filepath.Join(base, "embedded_tools", "jdk")
		}
	}
	return ""
}
