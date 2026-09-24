# Daemonitor Native CLI/TUI Specification

Status: Proposed  
Target: Daemonitor 1.x follow-up  
Primary implementation: Go  
UI framework: Bubble Tea v2, Bubbles, and Lip Gloss

## 1. Summary

Replace the current Kotlin terminal renderer with a native Go terminal UI that behaves like `top` or `htop`.

The new CLI will remain a thin client of `daemonitor-cored`. The core will continue to collect process data, persist history, parse daemon logs, and expose its local HTTP API over the existing socket. The CLI will own terminal behavior only: rendering, keyboard interaction, resizing, sorting, selection, and process details.

The existing one-shot and JSON commands will remain available for scripts and agent integrations.

## 2. Motivation

The current CLI periodically prints a complete ANSI frame and checks `InputStream.available()` for `q`. This provides refreshed output but not a complete interactive terminal experience.

Current limitations:

- Input is not read in terminal raw mode, so `q` may require Enter.
- The whole screen is cleared on each refresh, which can flicker and affect scrollback.
- The layout does not respond to terminal resizing.
- Fixed-width columns can wrap in narrow terminals.
- There is no row selection, scrolling, sorting, pause, or details view.
- Terminal detection and color behavior are coupled to screen clearing.
- The Kotlin CLI requires JDK 21 even when data collection is performed by the Go core.

## 3. Goals

- Provide a responsive full-screen TUI comparable to `top` for normal monitoring workflows.
- React immediately to key presses without requiring Enter.
- Refresh process data without visibly clearing or rebuilding the terminal.
- Adapt the table to the current terminal width and height.
- Allow process selection, sorting, pausing, manual refresh, and details inspection.
- Preserve one-shot human-readable and JSON output for automation.
- Reuse the existing `daemonitor-cored` API and data model.
- Ship a native CLI that does not require a JDK.
- Work locally and through an allocated SSH terminal.
- Restore the terminal correctly after normal exit, Ctrl+C, failures, or cancellation.

## 4. Non-goals

- Reimplementing process collection in the TUI.
- Moving terminal concerns into `daemonitor-cored`.
- Adding live JVM heap collection to the Go core.
- Replacing the desktop Compose application.
- Replacing the existing SQLite schema or local HTTP API.
- Providing remote network access to the core API.
- Reproducing every desktop visualization in the first TUI release.

## 5. Product model

The product remains split into two responsibilities:

| Component | Responsibility |
| --- | --- |
| `daemonitor-cored` | Process polling, classification, persistence, daemon-log parsing, build aggregation, retention, and local API |
| `daemonitor-cli` | Interactive TUI, one-shot commands, JSON output, core discovery, and core startup |

The CLI must access data through the core API. It must not read or write the SQLite database directly.

## 6. Command-line interface

The released command remains `daemonitor-cli` for compatibility.

### 6.1 Commands

```text
daemonitor-cli                 Open the interactive monitor when attached to a TTY
daemonitor-cli top             Open the interactive monitor
daemonitor-cli ps              Print one process snapshot and exit
daemonitor-cli history         Print recent process history
daemonitor-cli builds          Print recent builds
daemonitor-cli logs            List discovered Gradle daemon logs
daemonitor-cli logs <pid>      Show the retained log tail for one daemon
daemonitor-cli health          Show core health and connection information
```

`daemonitor-corectl` may remain temporarily as an internal diagnostic binary. Its commands should be migrated to `daemonitor-cli` before it is removed.

### 6.2 Common options

```text
--socket PATH                  Use an explicit core socket
--poll-interval DURATION       TUI refresh interval, default 2s
--no-color                     Disable color while preserving TUI behavior
--no-autostart                 Do not start daemonitor-cored automatically
--json                         Emit machine-readable output where supported
-h, --help                     Show help
-v, --version                  Show version
```

### 6.3 TTY behavior

- `daemonitor-cli` with an interactive TTY opens the TUI.
- `daemonitor-cli top` requires an interactive TTY. If none is available, it exits with a clear message recommending `ps` or `ps --json`.
- `daemonitor-cli` without a TTY prints one plain process snapshot and exits. It must not continuously append dashboard frames to redirected output.
- `--json` is not valid with `top`.
- `NO_COLOR` and `--no-color` disable colors, but do not disable alternate-screen rendering or keyboard interaction.

## 7. Interactive experience

### 7.1 Default screen

The initial screen contains three regions:

1. Summary header
2. Navigable process table
3. Contextual help footer

Example content:

```text
DAEMONITOR                                      Updated 10:42:18
6 processes   5.8 GB RSS   Core connected   Refresh 2s

TYPE              PID      RSS     CPU    XMX      UPTIME   PROJECT
Gradle daemon     43122    2.1 GB   84.2%  4 GB     12m      daemonitor
Kotlin daemon     43091    1.3 GB   18.0%  2 GB     14m      daemonitor

↑/↓ or j/k select   s sort   space pause   r refresh   enter details   q quit
```

The exact styling may evolve, but the information hierarchy and key behavior are requirements.

### 7.2 Keyboard controls

| Key | Action |
| --- | --- |
| `q`, `Ctrl+C` | Exit |
| `↑`, `k` | Select previous process |
| `↓`, `j` | Select next process |
| `Home`, `g` | Select first process |
| `End`, `G` | Select last process |
| `s` | Cycle sort field |
| `S` | Reverse sort direction |
| `space` | Pause or resume automatic refresh |
| `r` | Refresh immediately |
| `enter` | Open process details |
| `esc` | Close details and return to the table |
| `?` | Toggle expanded help |

Mouse support is optional and must not be required for any action.

### 7.3 Sorting

Default sorting is RSS descending, then PID ascending.

The first release supports sorting by:

- RSS
- CPU
- PID
- Type
- Uptime
- Project

The active sort field and direction must be visible in the column header. The selection must follow the selected PID when new snapshots reorder rows.

### 7.4 Refresh and pause

- The default refresh interval is two seconds.
- Data fetching must not block keyboard or resize handling.
- `space` pauses network polling and leaves the current snapshot visible.
- `r` performs a refresh even while paused.
- The header must clearly show `PAUSED` while polling is paused.
- A slow request must not create concurrent requests for the same snapshot.

### 7.5 Process details

Pressing Enter opens a details view or pane for the selected process.

The first version should show available fields from the current API:

- Process type
- PID
- Process name
- Project or working directory
- RSS
- CPU
- Configured maximum heap
- Start time and uptime
- Associated Gradle version when available
- Relevant daemon log path when available
- Recent redacted daemon-log lines when available

Unavailable data is displayed as `n/a`. The TUI must not imply that live heap is available through the Go core when it is not.

### 7.6 Empty and error states

When no relevant processes are running, show a stable empty state rather than an empty table:

```text
No Gradle-related processes are currently running.
Waiting for activity…
```

If a refresh fails after a successful snapshot:

- Keep the last successful snapshot visible.
- Show a concise error in the header or status area.
- Retry at the next interval.
- Do not print repeated errors below the TUI.

If no connection has ever succeeded:

- Show connection progress while autostart is attempted.
- Show a clear terminal error if startup or connection fails.
- Include the socket path and one actionable recovery suggestion.

## 8. Responsive layout

The TUI must render according to terminal dimensions received from resize events.

Suggested visibility order:

| Width | Visible data |
| --- | --- |
| 110 columns or more | Type, PID, RSS, heap used when available, heap committed when available, Xmx, CPU, uptime, project |
| 80–109 columns | Type, PID, RSS, Xmx, CPU, uptime, shortened project |
| Below 80 columns | Type, PID, RSS, CPU, shortened project |

Additional requirements:

- Content must never intentionally exceed the terminal width.
- Project and process names use Unicode-aware truncation with an ellipsis.
- The header and footer may collapse to multiple rows when necessary.
- The table height must be derived from available rows.
- When processes exceed the visible height, the selected row remains in view.
- A very small terminal shows a concise request to enlarge it rather than corrupted output.

## 9. Architecture

### 9.1 Proposed packages

```text
cored/
  cmd/
    daemonitor-cored/
    daemonitor-cli/
  internal/
    client/          Socket HTTP client and API decoding
    command/         Command parsing and one-shot commands
    tui/             Bubble Tea model, update, view, keys, and styles
    render/          Plain-text and JSON rendering
```

The existing core models should be reused where practical. API response types must not depend on Bubble Tea types.

### 9.2 TUI state model

The Bubble Tea model should own presentation state only:

```go
type Model struct {
    width        int
    height       int
    processes    []model.Process
    selectedPID  int64
    sortField    SortField
    sortOrder    SortOrder
    paused       bool
    loading      bool
    detailsOpen  bool
    lastUpdated  time.Time
    lastError    error
}
```

Core data is fetched through commands that return messages to the update loop. The update function must remain free of blocking I/O.

Expected message types include:

```text
tickMsg
snapshotLoadedMsg
snapshotFailedMsg
tea.KeyPressMsg
tea.WindowSizeMsg
```

### 9.3 Data flow

```text
Bubble Tea timer
      ↓
Socket API client → GET /v1/processes
      ↓
Snapshot message
      ↓
Update model, preserve selected PID, apply sorting
      ↓
Render changed terminal cells
```

Process details may fetch logs lazily after the user opens the details view.

### 9.4 Core discovery and startup

The Go CLI should reproduce the existing packaged behavior:

1. Resolve the default or explicit socket.
2. Call `/v1/health`.
3. If unavailable and autostart is enabled, locate the sibling `daemonitor-cored` binary.
4. Start the core without attaching its normal logs to the TUI output.
5. Wait for health with a bounded timeout.
6. Connect or return a clear error.

Exiting the TUI must not stop an already-running shared core. Duplicate-core prevention remains the responsibility of the core/socket startup layer.

## 10. Terminal lifecycle

The TUI must use an alternate screen and raw keyboard input.

Requirements:

- The user's normal scrollback is restored on exit.
- The cursor is restored on exit.
- Terminal modes are restored after `q`, Ctrl+C, SIGTERM, connection failure, or application error.
- No application logs are written directly to stdout while the TUI owns the terminal.
- Diagnostic logging, when enabled, is written to a file or collected in memory.
- Color detection is independent from interactivity detection.
- `TERM=dumb` disables the TUI and produces a useful fallback message.

Bubble Tea should own raw-mode setup, alternate-screen behavior, input decoding, resize messages, and terminal restoration wherever possible.

## 11. Plain and JSON output

Interactive output and scriptable output are separate modes.

### Plain output

- Prints one stable snapshot.
- Contains no ANSI control sequences when redirected.
- Uses a deterministic column order.
- Returns exit code zero when the request succeeds, including an empty process list.

### JSON output

- Uses the core API schema or a documented stable CLI schema.
- Writes JSON data to stdout only.
- Writes diagnostics to stderr.
- Does not include colors, progress messages, or banners.
- Returns a nonzero exit code on connection or decoding failure.

## 12. Compatibility and migration

### Phase 1: Parallel implementation

- Add the Go `daemonitor-cli` binary alongside the Kotlin CLI.
- Match `health`, `ps`, `history`, `builds`, and log commands.
- Build and test the TUI without changing the default release artifact.

### Phase 2: Preview release

- Include the Go CLI in release assets under a preview name if needed.
- Test on macOS, Linux, Windows, common terminal emulators, and SSH.
- Collect feedback about layout, keybindings, and core startup.

### Phase 3: Default CLI

- Make the Go binary the released `daemonitor-cli`.
- Preserve existing flags where they still make sense.
- Map the old no-subcommand behavior to the new TUI.
- Keep the Kotlin CLI available for one release as a fallback if required.

### Phase 4: Removal

- Remove the Kotlin CLI module after feature and packaging parity.
- Remove or internalize `daemonitor-corectl` after its useful commands exist in the main CLI.
- Remove the JDK dependency from the CLI installation instructions and Homebrew formula.

## 13. Testing strategy

### Unit tests

- Sorting for every supported field and direction.
- Selection preservation by PID across refreshes and reordered rows.
- Selection behavior when the selected process exits.
- Adaptive column selection at defined terminal widths.
- Unicode-aware truncation.
- Key mapping and pause behavior.
- Empty, loading, connected, stale, and error states.
- Plain and JSON output stability.

### Model/update tests

Feed deterministic Bubble Tea messages into the model and assert the resulting state. Network access and wall-clock time must be replaceable with fakes.

### Golden rendering tests

Render fixed models at representative sizes, including:

- 60×15
- 80×24
- 120×30
- Empty process list
- More processes than visible rows
- Error with a previous successful snapshot
- Details view

Golden tests should normalize color when color is not the subject of the test.

### Integration tests

- Start a temporary core and fetch a live snapshot through the socket client.
- Verify core autostart from the packaged layout.
- Verify `ps --json` remains valid when stdout is redirected.
- Verify the TUI starts and exits inside a pseudo-terminal.
- Verify `q` exits without Enter.
- Verify resize events update the rendered layout.
- Verify terminal state is restored after exit and interruption.

### Platform matrix

- macOS arm64 and amd64
- Linux arm64 and amd64
- Windows arm64 and amd64 where supported by the existing release matrix
- SSH session with a pseudo-terminal
- At least one minimal or `TERM=dumb` fallback case

## 14. Performance requirements

- Idle TUI CPU should remain negligible between refreshes.
- Only one snapshot request may be active at a time.
- Keyboard and resize handling must remain responsive during API requests.
- A normal refresh must not visibly flicker.
- Rendering 200 process rows should remain interactive, with only visible rows drawn.
- TUI memory usage should remain small relative to the monitored Gradle processes.
- Details and log-tail data should be loaded lazily.

## 15. Privacy and security

- Preserve the existing local-only socket/API model.
- Do not add a TCP listener for the TUI.
- Display only data already redacted by the core or shared redaction layer.
- Do not write command lines, log lines, or process details to new files unless diagnostic logging is explicitly enabled.
- JSON and plain modes must follow the same redaction rules as the TUI.
- Error messages must not expose secret values from environment variables or command lines.

## 16. Packaging

- Produce native binaries for the same operating systems and architectures as `daemonitor-cored`.
- Release packages include both `daemonitor-cli` and `daemonitor-cored`.
- The CLI locates the sibling core without depending on the current working directory.
- Homebrew installation must not require Java after the migration is complete.
- Direct archive installation must work without Gradle, Kotlin, or a JDK.
- Version output should identify the CLI version and, in `health`, the connected core version.

## 17. Acceptance criteria

The native CLI is ready to replace the Kotlin CLI when all of the following are true:

- `daemonitor-cli` opens a full-screen monitor in a supported interactive terminal.
- `q` exits immediately without Enter.
- Arrow keys and `j`/`k` move the selection.
- The table updates without full-screen flicker.
- Resizing the terminal updates the layout without wrapping or corruption.
- RSS, CPU, PID, process type, uptime, project, and Xmx are shown when available.
- Sorting, pause, refresh, and details actions work as specified.
- The selected PID remains selected across refreshes when it still exists.
- Connection errors retain the last successful snapshot and recover automatically.
- `ps` and `ps --json` work without a TTY.
- Redirected output contains no terminal control sequences.
- The terminal is restored after normal exit, Ctrl+C, and failure.
- The packaged CLI can discover or start the packaged core.
- Installation and execution do not require a JDK.
- Existing privacy and redaction behavior is preserved.
- CI covers supported platforms and includes a pseudo-terminal smoke test.

## 18. Suggested implementation slices

### Slice 1: Interactive process table

- Add Bubble Tea dependencies.
- Extract the existing Go socket client from `daemonitor-corectl`.
- Render the current process snapshot in an alternate screen.
- Support timer refresh, `q`, Ctrl+C, and resize.

### Slice 2: Navigation and responsive layout

- Add selection and scrolling.
- Add adaptive columns.
- Preserve selection by PID.
- Add help footer and narrow-terminal state.

### Slice 3: Operational controls

- Add sorting, pause, manual refresh, and recoverable error display.
- Add non-TTY and `TERM=dumb` behavior.
- Add one-shot plain and JSON commands.

### Slice 4: Process details

- Add details view.
- Load daemon-log metadata and redacted tail lazily.
- Add navigation back to the process table.

### Slice 5: Packaging cutover

- Add native binaries to the release matrix.
- Update Homebrew and archive packaging.
- Run pseudo-terminal and packaged autostart tests.
- Replace the Kotlin CLI after parity is confirmed.

## 19. Initial decisions

- Use Bubble Tea v2 instead of implementing terminal control directly.
- Keep `daemonitor-cored` and the TUI as separate processes.
- Keep the released command name `daemonitor-cli` during migration.
- Default to RSS-descending sorting.
- Preserve selection by PID, not row number.
- Use a two-second refresh interval by default.
- Keep plain and JSON modes separate from the interactive TUI.
- Treat live heap as unavailable on the Go path until the core supports it.

## 20. Open questions

- Should the final user-facing command eventually become `daemonitor`, with `daemonitor-cli` retained as an alias?
- Should process details use a right-side pane on wide terminals and a full-screen view on narrow terminals?
- Should the selected sort field persist between sessions?
- Should a future release expose build history as a second TUI tab, or keep it as a separate command?
- Should the CLI expose a diagnostic log flag for troubleshooting terminal or socket problems?
