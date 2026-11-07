# Process instances and lifecycle events

Daemonitor identifies each process incarnation by **host id + PID + OS create time**. PID reuse therefore cannot merge counter priors or instance history.

## Storage (schema v5)

| Table | Role |
|---|---|
| `process_instances` | Durable identity, type, command-line summary, first/last observed, optional `disappeared_at_ms` |
| `process_events` | Bounded lifecycle stream (`schema_version` 1) |

`process_samples` and `builds` are unchanged. Retention purge removes old samples, events, and instances on the same window.

## Events

| `event_type` | When |
|---|---|
| `process.first_observed` | New incarnation appears in a poll |
| `process.disappearance_detected` | Previously alive incarnation missing from a poll (detection time only — no fabricated exit code) |
| `process.configuration_observed` | Command line or process type changes for the same incarnation |

## Local API

`GET /v1/events?since_ms=&limit=` returns:

```json
{
  "schema_version": 1,
  "since_ms": 0,
  "count": 1,
  "events": [
    {
      "schema_version": 1,
      "event_type": "process.first_observed",
      "timestamp_ms": 1793979720000,
      "process_instance_id": "host:8421:1793979000000",
      "pid": 8421,
      "process_type": "KOTLIN_DAEMON",
      "source": "process_poll"
    }
  ]
}
```

CPU percent priors in `poll.Collector` are keyed by `(pid, create_time_ms)`, matching heap probe cache identity.
