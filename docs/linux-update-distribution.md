# Linux Update Distribution

## Decision

Daemonitor will keep the initial Linux release path as a direct `.deb` asset on GitHub Releases,
plus a `.tar.gz` update package for standalone installs that can self-update. Longer term, Linux
package updates should move to a signed apt repository as the preferred channel for package-managed
installs.

The app must not try to self-install updates on Linux installations owned by the system package
manager. Prompts should describe the available version and point people at the right path:

- If the current install came from the apt repository, update through the system package manager.
- If the current install came from a downloaded `.deb`, download and open the newer GitHub Releases
  `.deb`.
- If the current install is a writable standalone / archive layout, Daemonitor may download the
  matching `.tar.gz`, stage it, and apply it after **Restart and Update**.
- If the install source is unknown, offer the manual package path and note that apt is preferred
  once a repository is configured.

That keeps package-manager installs separate from repository setup and avoids privilege escalation
or replacing `dpkg`/`rpm`-owned files from inside the app.

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

The apt repository is not required before shipping the Phase 1 updater prompt. Prefer apt once
hosting, metadata, and signing exist.

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
- A signed `Release` or `InRelease` file
- Public signing key + documented user setup
- A private signing key managed only in release infrastructure, never in the app or this repo
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
