# Update Metadata Contract

Daemonitor publishes update metadata to each tag-triggered GitHub Release so updater clients can
validate release information and native downloads before staging or opening installers. The desktop
app uses this metadata to download the selected artifact in-app, verify it, and either stage an
automatic **Restart and Update** package or hand off to the operating system installer.

## Release assets

Each release includes these machine-readable files:

- `latest.json`: canonical metadata for the current release.
- `update.json`: an alias with the same content for updater clients that prefer an update-specific
  file name.
- `checksums.txt`: SHA-256 checksums for every native installer and update package, in the common
  `<sha256>  <fileName>` format.
- `<asset>.sha256`: per-asset SHA-256 sidecars for the in-app updater and manual verification.

The release also includes platform/architecture native installers and update packages:

### Installers (first-time / manual)

- `Daemonitor-<version>-linux-<arch>.deb`
- `Daemonitor-<version>-windows-<arch>.msi`
- `Daemonitor-<version>-macos-<arch>.dmg`

### Update packages (in-app Restart and Update)

- `Daemonitor-<version>-linux-<arch>.tar.gz`
- `Daemonitor-<version>-windows-<arch>.zip`
- `Daemonitor-<version>-macos-<arch>.zip`

`<version>` is read from the Gradle `printNativePackageVersion` task during the tag-triggered
release workflow. `<arch>` is `x64` or `arm64` for the runner that built the asset. The same values
are used for installer names, update package names, and metadata.

## JSON schema

`latest.json` and `update.json` currently use `schemaVersion` `2`:

```json
{
  "schemaVersion": 2,
  "name": "Daemonitor",
  "version": "1.0.7",
  "tag": "v1.0.7",
  "repository": "https://github.com/cdsap/daemonitor",
  "assets": [
    {
      "platform": "macos",
      "arch": "arm64",
      "role": "update",
      "fileName": "Daemonitor-1.0.7-macos-arm64.zip",
      "url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-arm64.zip",
      "sha256": "<64 lowercase hex characters>",
      "size": 123456
    },
    {
      "platform": "macos",
      "arch": "arm64",
      "role": "installer",
      "fileName": "Daemonitor-1.0.7-macos-arm64.dmg",
      "url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.7/Daemonitor-1.0.7-macos-arm64.dmg",
      "sha256": "<64 lowercase hex characters>",
      "size": 123456
    }
  ]
}
```

Clients should treat `schemaVersion`, `version`, `tag`, and `assets` as required. Each asset entry
must include `platform`, `fileName`, `url`, `sha256`, and `size`. Schema `2` also includes `arch`
(`x64` / `arm64`) and `role` (`update` / `installer`).

Schema `1` metadata without `arch`/`role` remains readable: clients infer role from the file
extension and treat missing architecture as a legacy fallback for the current OS.

## Client validation

Updater clients should:

1. Download `latest.json` or `update.json` from the GitHub Release.
2. Confirm `schemaVersion` is supported (`1` or `2`).
3. Detect the current operating system and CPU architecture.
4. Select the asset matching the current platform and architecture.
5. Prefer `role=update` packages when the installation supports automatic Restart and Update.
6. Prefer `role=installer` assets when automatic installation is unavailable.
7. Download the asset from `url` only from the official `cdsap/daemonitor` GitHub repository.
8. Compute SHA-256 over the downloaded bytes and compare it with `sha256`.
9. Stage validated update packages without terminating Daemonitor, or open verified installers only
   after the user approves the update.
10. Apply staged updates only after the user chooses **Restart and Update**.

Clients may also download `checksums.txt` or `<asset>.sha256` and compare them against the JSON
asset list as a secondary integrity check.

A release is not eligible for automatic updates on a given machine when the required
platform/architecture update package is missing.
