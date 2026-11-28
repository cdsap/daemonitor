package command

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/cdsap/daemonitor/cored/internal/bootstrap"
	"github.com/cdsap/daemonitor/cored/internal/client"
	"github.com/cdsap/daemonitor/cored/internal/poll"
	"github.com/cdsap/daemonitor/cored/internal/render"
	"github.com/cdsap/daemonitor/cored/internal/sessions"
	"github.com/shirou/gopsutil/v4/process"
)

func runObserve(ctx context.Context, opts Options, rest []string, stdout, stderr io.Writer) int {
	cmdArgs := opts.CommandArgs
	if len(cmdArgs) == 0 {
		cmdArgs = rest
	}
	if len(cmdArgs) == 0 {
		fmt.Fprintln(stderr, "daemonitor-cli: observe requires a command after -- (for example: daemonitor-cli observe -- xcodebuild ...)")
		return 2
	}

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

	cwd, _ := os.Getwd()
	rawCmdLine := strings.Join(cmdArgs, " ")
	ecosystem := opts.Ecosystem
	if ecosystem == "" {
		ecosystem = detectEcosystem(cmdArgs[0])
	}
	projectPath := opts.Project
	if projectPath == "" {
		var cwdPtr *string
		if cwd != "" {
			cwdPtr = &cwd
		}
		if ecosystem == "xcode" {
			if extracted := poll.ExtractXcodeProjectPath(rawCmdLine, cwdPtr); extracted != nil {
				projectPath = *extracted
			}
		} else {
			projectPath = cwd
		}
	}

	cmd := exec.CommandContext(ctx, cmdArgs[0], cmdArgs[1:]...)
	cmd.Stdin = os.Stdin
	cmd.Stdout = stdout
	cmd.Stderr = stderr

	startedAtMs := time.Now().UnixMilli()
	if err := cmd.Start(); err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: start %q: %v\n", cmdArgs[0], err)
		return 127
	}

	childPID := int32(cmd.Process.Pid)
	var childStartMs int64
	if proc, err := process.NewProcessWithContext(ctx, childPID); err == nil {
		if ct, err := proc.CreateTimeWithContext(ctx); err == nil {
			childStartMs = ct
		}
	}

	opened, openErr := c.OpenSession(ctx, sessions.BuildSessionOpenRequest{
		Ecosystem:         ecosystem,
		Mode:              "explicit",
		RootProjectPath:   projectPath,
		CommandLine:       poll.RedactCommandLine(rawCmdLine),
		AnchorPID:         childPID,
		AnchorStartTimeMs: childStartMs,
		StartedAtMs:       startedAtMs,
	})
	if openErr != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: warning: could not open observation session: %v\n", openErr)
	}

	waitErr := cmd.Wait()
	endedAtMs := time.Now().UnixMilli()

	exitCode := 0
	outcome := "success"
	if waitErr != nil {
		outcome = "failed"
		var exitErr *exec.ExitError
		if errors.As(waitErr, &exitErr) {
			exitCode = exitErr.ExitCode()
		} else if errors.Is(ctx.Err(), context.Canceled) {
			outcome = "interrupted"
			exitCode = 130
		} else {
			exitCode = 1
		}
	}

	if openErr == nil && opened.SessionID != "" {
		closeCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		summary, closeErr := c.CloseSession(closeCtx, opened.SessionID, sessions.BuildSessionCloseRequest{
			EndedAtMs: endedAtMs,
			Outcome:   outcome,
		})
		if closeErr != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: warning: could not close observation session: %v\n", closeErr)
		} else {
			writeObserveSummary(summary, opts, stderr)
		}
	}

	return exitCode
}

func writeObserveSummary(summary sessions.BuildSessionSummary, opts Options, stderr io.Writer) {
	if opts.OutputPath != "" {
		f, err := os.OpenFile(opts.OutputPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
		if err == nil {
			_ = render.WriteJSON(f, summary)
			_ = f.Close()
		} else {
			fmt.Fprintf(stderr, "daemonitor-cli: warning: could not write summary to %s: %v\n", opts.OutputPath, err)
		}
	}
	fmt.Fprintln(stderr)
	if opts.Output == "json" {
		_ = render.WriteJSON(stderr, summary)
		return
	}
	if opts.Output == "jsonl" {
		_ = render.WriteJSONLine(stderr, summary)
		return
	}
	render.WriteSessionSummaryPlain(stderr, summary)
}

func runSessions(ctx context.Context, c *client.Client, opts Options, args []string, stdout, stderr io.Writer) int {
	if len(args) > 0 {
		summary, err := c.GetSession(ctx, args[0])
		if err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		if err := writeValue(stdout, summary, opts.Output, func() { render.WriteSessionSummaryPlain(stdout, summary) }); err != nil {
			fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
			return 1
		}
		return 0
	}

	limit := opts.Limit
	if limit == 0 {
		limit = 50
	}
	list, err := c.ListSessions(ctx, opts.Ecosystem, limit)
	if err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	if err := writeValue(stdout, map[string]any{"count": len(list), "sessions": list}, opts.Output, func() {
		render.WriteSessionsPlain(stdout, list)
	}); err != nil {
		fmt.Fprintf(stderr, "daemonitor-cli: %v\n", err)
		return 1
	}
	return 0
}

func detectEcosystem(binary string) string {
	base := strings.ToLower(filepath.Base(binary))
	switch base {
	case "xcodebuild", "swift", "swiftc":
		return "xcode"
	case "gradlew", "gradle":
		return "gradle"
	case "bazel", "bazelisk":
		return "bazel"
	default:
		return "generic"
	}
}
