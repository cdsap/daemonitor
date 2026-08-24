# Mac App Store Distribution

Issue: cdsap/daemonitor#108

## Recommendation

Do **not** ship Daemonitor on the Mac App Store until a signed, sandboxed App Store build proves
that same-UID Gradle/JVM process introspection still returns usable command lines, working
directories, CPU, and RSS under App Sandbox. That capability is load-bearing for Live Monitor and
has **no public App Store entitlement**.

Keep **direct distribution** (signed/notarized DMG + GitHub Releases updater) as the primary macOS
channel. Introduce an `APP_STORE` packaging/runtime variant so sandbox packaging, entitlement
minimums, updater gating, and `~/.gradle` access design can be validated without changing the
default `DIRECT` release path.

## Distribution channels

| Channel | Package | Updates | Sandbox |
|---------|---------|---------|---------|
| `DIRECT` (default) | GitHub Releases `.dmg` / `.zip` | In-app GitHub Releases updater | Not required (Developer ID + notarization) |
| `APP_STORE` | App Store `.pkg` / TestFlight | Apple only — in-app GitHub updater disabled | Required |

Select the channel at package time:

```bash
./gradlew packageDmg -Pdaemonitor.distribution=DIRECT
./gradlew packagePkg -Pdaemonitor.distribution=APP_STORE
```

The selected value is embedded in `daemonitor-build.properties` as `distribution=` and exposed to
runtime code as `DistributionChannel`.

## Sandbox validation matrix

Status meanings:

- **Compatible** — expected to work with listed entitlements.
- **Requires entitlement** — needs an explicit App Sandbox entitlement.
- **Requires user grant** — needs security-scoped bookmark / Open panel for `~/.gradle`.
- **Likely incompatible** — no public entitlement; App Review temporary exceptions are unreliable.
- **Runtime proof required** — code path exists, but only a sandboxed App Store-signed build can confirm.

| Feature | Status | Notes |
|---------|--------|-------|
| Detect running Gradle/JVM processes | Runtime proof required / Likely incompatible | OSHI enumerates same-UID processes via sysctl/`proc_*`. App Sandbox commonly blanks or denies other-process argv; without command lines, Gradle classification degrades or fails. |
| Read CPU and RSS | Runtime proof required | Often still available for same-UID processes, but must be confirmed under the App Store sandbox + JVM runtime entitlements. |
| Read process command line / JVM arguments | Likely incompatible | `KERN_PROCARGS2` / equivalent access is typically denied in App Sandbox. No public entitlement restores it. |
| Resolve process working directories | Likely incompatible | Same restriction family as argv; cwd is best-effort today even outside the sandbox. |
| Read and monitor `~/.gradle/daemon/` | Requires user grant | Outside the container. Direct home access is blocked unless a temporary home-relative exception is approved (App Review risk) or the user grants folder access. |
| Security-scoped bookmarks for `~/.gradle` | Requires user grant | Use `com.apple.security.files.user-selected.read-only` + `com.apple.security.files.bookmarks.app-scope`, persist bookmark data under Application Support, and start access before daemon-log reads. Native bookmark APIs are not yet wired; App Store builds must treat missing grants as a blocked Gradle home. |
| Local MCP server on `127.0.0.1` | Requires entitlement | Compatible with `com.apple.security.network.server` (and client only if outbound calls remain). App Store builds must not use the GitHub updater. |
| Desktop → headless/background mode | Runtime proof required | Relaunching the same signed bundle should stay inside the sandbox. Tray/menu-bar helpers and ProcessBuilder relaunch need smoke tests on a sandboxed build. |
| GitHub Releases in-app updater | Incompatible with App Store rules | Disabled for `APP_STORE`. Updates are managed by Apple. |

## Minimum App Sandbox entitlements

App + JVM runtime entitlements for an App Store experiment live under `packaging/macos/`:

- `app-store.entitlements`
- `app-store-runtime.entitlements`

Minimum keys for Daemonitor's intended App Store surface:

| Entitlement | Why |
|-------------|-----|
| `com.apple.security.app-sandbox` | Required for App Store / TestFlight |
| `com.apple.security.cs.allow-jit` | JVM |
| `com.apple.security.cs.allow-unsigned-executable-memory` | JVM |
| `com.apple.security.cs.disable-library-validation` | JVM / bundled native libs (SQLDelight/SQLite, OSHI helpers) |
| `com.apple.security.network.server` | Bind MCP HTTP to `127.0.0.1` |
| `com.apple.security.files.user-selected.read-only` | User-picked `~/.gradle` (or parent) access |
| `com.apple.security.files.bookmarks.app-scope` | Persist that grant across launches |

Optional / high App Review risk:

| Entitlement | Why avoid by default |
|-------------|----------------------|
| `com.apple.security.temporary-exception.files.home-relative-path.read-only` for `.gradle/` | May restore daemon-log monitoring without a folder picker, but temporary exceptions are frequently rejected. Prefer security-scoped bookmarks. |
| Any temporary exception aimed at process argv/cwd | There is no supported App Store path for unrestricted process introspection. |

Provisioning profiles, Apple Distribution signing identity, and `embedded.provisionprofile` files are
**not** checked into the repository. Supply them only on the App Store packaging runner.

## Incompatible or high-risk behavior

1. **Core Live Monitor classification** depends on other-process command lines. If the sandbox blanks
   argv, Daemonitor cannot reliably classify Gradle daemons, wrappers, Kotlin daemons, or test
   workers — a functional loss, not a packaging polish issue.
2. **Unbounded `~/.gradle` reads** without user grant or an approved temporary exception.
3. **Self-updating from GitHub Releases** inside an App Store build (Guideline / App Store Review).
4. **Outbound update checks** on App Store builds — disable with the `APP_STORE` channel.
5. **Extracting and executing unsigned native code outside the bundle** — App Store sandbox builds
   must ship required native libraries inside the app resources layout.

## Runtime behavior for `APP_STORE`

- `DistributionChannel.APP_STORE` is read from build metadata.
- `UpdateService` returns `UpdateCheckResult.ManagedByAppStore` and never contacts GitHub Releases.
- Settings shows that updates are managed by the Mac App Store; no download/restart actions.
- Gradle daemon-home probing reports when `~/.gradle` is unreadable so a future bookmark flow can
  prompt the user instead of silently showing empty history.

## What still must be proven on hardware

Create a sandboxed App Store-signed build (local Developer ID sandbox experiment is not sufficient
for final sign-off) and verify:

1. OSHI still returns Gradle process rows with command line + RSS + CPU for same-UID JVMs.
2. Working directories remain populated often enough for project attribution.
3. After granting `~/.gradle` via Open panel + bookmark, daemon log discovery and tailing work.
4. MCP binds to `127.0.0.1` and answers authenticated requests.
5. Desktop → headless relaunch and tray actions still work.
6. App Review entitlement set is limited to the minimum table above.

## Decision

Dual-channel architecture is the right end state, but **App Store distribution is not ready to
replace or ship beside DIRECT** until process introspection is proven under sandbox. Implement the
`DIRECT` / `APP_STORE` variant and entitlement scaffolding now; keep releasing macOS users through
GitHub Releases until the sandbox matrix above is green on a real App Store build.
