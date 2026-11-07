# Process memory metrics

`GET /v1/processes` exposes portable resident memory together with native
measurements when the operating system provides them. Values are bytes and a
`null` value means unsupported or unavailable; a field is never relabeled from
another measurement.

| JSON field | Semantics |
|---|---|
| `resident_bytes` | Resident set / working set (RSS on Unix). |
| `virtual_bytes` | Virtual address-space size. |
| `private_committed_bytes` | Private committed memory, including Windows `PrivateUsage`. |
| `proportional_bytes` | Linux PSS from `/proc/<pid>/smaps_rollup`; sampled every 5 seconds by default. |
| `physical_footprint_bytes` | macOS `ri_phys_footprint`. This is not RSS or PSS. |
| `swap_bytes` | Process pages reported as swapped out, not pagefile reservation. |
| `peak_resident_bytes` | Native peak resident/working-set value when available. |

RSS, PSS, and macOS physical footprint have different ownership and sharing
semantics and must not be compared as if they were interchangeable. Linux PSS
is intentionally slower than RSS because reading `smaps_rollup` is more
expensive. Unsupported native fields remain `null`.
