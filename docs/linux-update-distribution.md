# Linux Update Distribution

## Decision

Ship Linux first as a GitHub Releases `.deb`, plus a `.tar.gz` for standalone installs that can
self-update. Longer term, prefer a signed apt repository for package-managed installs.

Never self-install updates into a package-manager-owned install. Prompts should describe the new
version and point people at the right path:

- **apt install** → update via the system package manager
- **downloaded `.deb`** → download and open the newer GitHub Releases `.deb`
- **writable standalone / archive** → download the matching `.tar.gz`, stage it, apply after
  **Restart and Update**
- **unknown** → offer the manual package path; prefer apt once a repo is available

That keeps package-manager installs separate from repo setup and avoids privilege escalation or
replacing `dpkg`/`rpm`-owned files from inside the app.

## Direct `.deb` download

Smallest viable path:

- Releases already publish `Daemonitor-<version>-linux-<arch>.deb`
- Users open it in the desktop installer, or run
  `sudo apt install ./Daemonitor-<version>-linux-<arch>.deb`
- Built by the existing `packageDeb` CI job; no repository metadata required

Tradeoff: no standard update feed. The app can announce a release, but install still needs an
explicit user action and package-manager privileges.

## Apt repository

Preferred long-term channel for package-managed installs:

- Upgrades via `apt update` / `apt upgrade`
- Dependency checks and replacement stay with the OS
- The app does not need privileged commands or an updater daemon

Not required for the Phase 1 advisory prompt. Prefer apt once hosting, metadata, and signing exist.

## Update prompt behavior

Package-managed prompts are advisory only:

- Show the available version and release link
- Never run `sudo`, `pkexec`, `apt`, `dpkg`, or other privileged installers from the app
- apt installs → tell the user to update via the package manager (link repo setup docs)
- direct `.deb` → may download and verify the GitHub asset, then open it with the OS installer
- standalone → may stage `.tar.gz` and apply only after **Restart and Update**
- Allow dismiss / defer so background collection is not blocked

## Package metadata

Before apt publication, keep metadata stable:

- Package name: `daemonitor`
- Display name: `Daemonitor`
- Version: same as GitHub Releases
- Architecture: from the Linux package job
- Maintainer: project maintainer contact
- Description: short desktop blurb plus a longer package description
- Homepage: `https://github.com/cdsap/daemonitor`
- License: MIT
- Dependencies: explicit runtime deps for the generated package
- Conflicts/Replaces/Provides: only if the name or layout changes later

Map one Git tag → one package version → one apt package entry.

## Hosting and signing

The apt repo needs:

- HTTPS host
- `dists/` + `pool/` (or equivalent via `reprepro` / `aptly`)
- `Packages` indexes (plain and compressed)
- Signed `Release` / `InRelease`
- Public signing key + documented user setup
- Private signing key only in release infra (never in the app or this repo)
- Automation to upload the `.deb`, regenerate indexes, sign, and verify `apt update`

GitHub Pages or another static HTTPS host works if automation can update repo files without exposing
the signing key.

## Follow-up

1. Keep publishing the GitHub Releases `.deb` in Phase 1
2. Prompt copy that distinguishes apt, direct `.deb`, and unknown — no privileged install
3. Choose hosting and signing-key storage
4. Automate apt metadata from the published `.deb`
5. CI: install the repo key/source on a clean Linux runner, `apt update`, confirm `daemonitor`
6. Prefer apt in install docs once live; keep direct `.deb` as fallback
