# Update Metadata Contract

Daemonitor publishes update metadata to each tag-triggered GitHub Release so updater clients can
validate release information and native downloads before presenting or opening installers.

## Release assets

Each release includes these machine-readable files:

- `latest.json`: canonical metadata for the current release.
- `update.json`: an alias with the same content for updater clients that prefer an update-specific
  file name.
- `checksums.txt`: SHA-256 checksums for every native installer, in the common
  `<sha256>  <fileName>` format.

The release also includes one native installer per supported platform:

- `Daemonitor-<version>-linux.deb`
- `Daemonitor-<version>-windows.msi`
- `Daemonitor-<version>-macos.dmg`

`<version>` is read from the Gradle `printNativePackageVersion` task during the tag-triggered
release workflow. The same value is used for installer names and metadata.

## JSON schema

`latest.json` and `update.json` currently use `schemaVersion` `1`:

```json
{
  "schemaVersion": 1,
  "name": "Daemonitor",
  "version": "1.0.2",
  "tag": "v1.0.2",
  "repository": "https://github.com/cdsap/daemonitor",
  "assets": [
    {
      "platform": "linux",
      "fileName": "Daemonitor-1.0.2-linux.deb",
      "url": "https://github.com/cdsap/daemonitor/releases/download/v1.0.2/Daemonitor-1.0.2-linux.deb",
      "sha256": "<64 lowercase hex characters>",
      "size": 123456
    }
  ]
}
```

Clients should treat `schemaVersion`, `version`, `tag`, and `assets` as required. Each asset entry
must include `platform`, `fileName`, `url`, `sha256`, and `size`.

## Client validation

Updater clients should:

1. Download `latest.json` or `update.json` from the GitHub Release.
2. Confirm `schemaVersion` is supported.
3. Select the asset matching the current platform.
4. Download the asset from `url`.
5. Compute SHA-256 over the downloaded bytes and compare it with `sha256` before presenting or
   opening the installer.

Clients may also download `checksums.txt` and compare it against the JSON asset list as a secondary
integrity check.
