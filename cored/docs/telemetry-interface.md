# Read-only agent telemetry interface

The native telemetry contract is versioned independently of the transport. The
contract package is the shared basis for CLI JSON/JSONL output, local export
files, and a future native MCP adapter.

## v1 boundary

The adapter may expose only these operations:

- `list_available_metrics`
- `list_process_instances`
- `query_process_metrics`
- `query_host_metrics`
- `query_process_events`
- `query_builds`

Every query operation has an explicit positive `from_ms`/`to_ms` window no wider
than seven days, and a row limit no greater than 5,000. Process-metric queries
must name a non-empty metric set. Listing operations are bounded by the same
response limit when a limit is supplied.

The first adapter is read-only. Diagnosis, remediation, process control,
unbounded streaming, automatic remote OpenTelemetry export, and default upload
are not part of this contract.

## Export rows

Local JSONL rows contain `schema_version: 1`, a record `kind`, collection time,
an operation-specific `payload`, and optional association evidence. An external
correlation ID is included only when explicitly supplied or derived from a
documented reliable source. `confirmed` and `inferred_temporal_overlap` are
different values; temporal proximity is never presented as confirmed identity.

All command lines, paths, log snippets, and event fields use the same redaction
policy as the CLI before crossing the interface. Exporting is an explicit local
file action with no network destination or upload behavior.
