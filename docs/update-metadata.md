# Update Metadata Contract

Each tag-triggered GitHub Release publishes metadata so clients can check version info and verify
downloads before staging or opening installers. The desktop app uses that to fetch the right
artifact, verify it, then either stage **Restart and Update** or hand off to the OS installer.

## Release assets

Machine-readable files on each release:

- `latest.json` — metadata for the current release
- `update.json` — same content, alternate name for some clients
- `checksums.txt` — SHA-256 lines in `<sha256>  <fileName>` form
- `<asset>.sha256` — per-asset sidecars for the in-app updater and manual checks

Native installers and update packages:

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

Clients should treat `schemaVersion`, `version`, `tag`, and `assets` as required. Each asset needs
`platform`, `fileName`, `url`, `sha256`, and `size`. Schema `2` also has `arch` (`x64` / `arm64`)
and `role` (`update` / `installer`).

Schema `1` without `arch`/`role` is still readable: infer role from the file extension, and treat
missing arch as a legacy fallback for the current OS.

## Client validation

1. Download `latest.json` or `update.json` from the GitHub Release.
2. Confirm `schemaVersion` is `1` or `2`.
3. Detect the current OS and CPU architecture.
4. Pick the asset for that platform and arch.
5. Prefer `role=update` when Restart and Update is supported; otherwise prefer `role=installer`.
6. Download only from the official `cdsap/daemonitor` repository URL.
7. Verify SHA-256 against `sha256`.
8. Stage update packages without quitting Daemonitor, or open verified installers only after the
   user approves.
9. Apply staged updates only after **Restart and Update**.

Optional: cross-check `checksums.txt` or `<asset>.sha256` against the JSON list.

Automatic updates are unavailable on a machine when the matching platform/arch update package is
missing from the release.
