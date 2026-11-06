# Windows IPC (TEL-IPC-001)

## Choice

Unix-domain sockets are not treated as a first-class Windows transport for
daemonitor-cored. On Windows, collector ↔ CLI IPC uses **named pipes** via
[`github.com/Microsoft/go-winio`](https://github.com/Microsoft/go-winio).

| OS | Default endpoint | Access control |
|----|------------------|----------------|
| macOS / Linux | `$appSupport/daemonitor-core.sock` | file mode `0600` |
| Windows | `\\.\pipe\daemonitor-core` | pipe SDDL: current user only (`D:P(A;;GA;;;<sid>)`) |

HTTP `/v1/*` routes are unchanged; only the listener/dialer differs. The `-socket` /
`--socket` flag accepts either a Unix socket path or a named-pipe path.

## Code

- Shared interface: `cored/internal/ipc.Transport` (`Listen` / `Dial`)
- Platform backends: `platform_unix.go` / `platform_windows.go`
- Defaults: `store.DefaultSocketPath()`

Autostart (`bootstrap.Connect`) skips creating a filesystem parent directory when the
endpoint is a named pipe; health still polls `/v1/health` over the same transport.
