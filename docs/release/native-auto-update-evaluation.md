# Native Auto-Update Evaluation

Issue: cdsap/daemonitor#50

## Recommendation

Do not adopt Sparkle 2 or WinSparkle until the Phase 1 manual-approved installer update path is
shipping and the release workflow can produce signed, notarized, platform-specific assets. Native
updaters should be a Phase 2 release-system feature, not an application-runtime prerequisite.

When Phase 1 is stable, adopt native updater support in this order:

1. Add a shared appcast publication step that describes the already-built release assets.
2. Pilot Sparkle 2 for macOS once the `.app` inside the `.dmg` is Developer ID signed and notarized.
3. Pilot WinSparkle for Windows once the `.msi` is Authenticode signed.
4. Keep Linux on the manual-approved installer/download path unless a separate package-repository
   strategy is chosen.

This path preserves Daemonitor's current trust posture: no silent update installs before there is a
signed update feed, signed installers, and a tested rollback/rotation process for updater keys.

## Current Release Baseline

Daemonitor is a Kotlin/JVM Compose Desktop app packaged by the Compose Gradle plugin through
`jpackage`. The current release workflow builds native packages on each target operating system:

- macOS: `packageDmg` uploads `Daemonitor-<version>-macos.dmg`.
- Windows: `packageMsi` uploads `Daemonitor-<version>-windows.msi`.
- Linux: `packageDeb` uploads `Daemonitor-<version>-linux.deb`.

The workflow creates a GitHub release, builds each asset, smoke-tests the packaged launcher, and
uploads the assets. It does not currently sign, notarize, generate appcasts, publish update metadata,
or run updater-specific verification.

## Sparkle 2 Assessment

Sparkle 2 is the right native macOS updater candidate after the macOS release pipeline is signed.
It is appcast-based, supports EdDSA update signatures, validates Apple code signatures, supports
sandboxed apps through bundled XPC services, and handles the native macOS update prompt/install flow.

Integration shape for this repo:

- Add Sparkle's public EdDSA key and feed URL to the generated macOS `Info.plist` with the Compose
  `macOS { infoPlist { extraKeysRawXml = ... } }` hook.
- Embed `Sparkle.framework` in `Daemonitor.app` and preserve symlinks, executable permissions, and
  nested helper signatures.
- Add a tiny macOS-native bootstrap layer that initializes Sparkle after the Compose window exists,
  or prove that a no-code configuration is sufficient for the generated app bundle. A JVM-only
  Compose app should not assume Sparkle initializes itself without this packaging proof.
- Publish a macOS appcast item for the signed archive, including `sparkle:edSignature`, version,
  minimum system version, download URL, length, and release notes.
- Gate automatic installation behind Sparkle's user-facing prompt/settings. Manual "Check for
  Updates" can be added later as a Compose action that calls the native bridge.

Release workflow changes required:

- Configure macOS bundle identifier, version/build version, Developer ID signing, hardened runtime,
  and notarization in the Compose native distribution.
- Sign Sparkle's framework, updater app, autoupdate binary, and XPC services correctly. Avoid broad
  `codesign --deep` signing because Sparkle's helper entitlements can differ.
- Generate and store the Sparkle EdDSA private key outside the repository, preferably in the CI
  secret store or macOS keychain used by the signing runner.
- Run Sparkle's `generate_appcast` or equivalent appcast-signing tool after the macOS asset is built
  and signed.
- Add updater smoke tests that install an older signed build, point it at a staging appcast, verify
  the prompt appears, and verify the updated app launches with the expected version.

Primary risks:

- Compose Desktop's generated app bundle is not an Xcode app, so framework embedding and startup
  initialization need a repo-owned packaging proof.
- Sparkle changes the macOS trust boundary. A leaked EdDSA private key or Apple signing identity
  can authorize malicious updates.
- Notarization and Sparkle helper signing failures tend to appear only on a real downloaded/quarantined
  app, so CI must test more than local `jpackage` output.

## WinSparkle Assessment

WinSparkle is the right Windows updater candidate after the Windows installer pipeline is signed. It
uses Sparkle-compatible appcasts, ships as a standalone `WinSparkle.dll`, exposes a C API, supports
EdDSA update signatures, and can launch an installer such as the current `.msi`.

Integration shape for this repo:

- Bundle `WinSparkle.dll` in the Windows application image created by the Compose native distribution.
- Add a small Kotlin/JVM wrapper using JNA or JNI for:
  - `win_sparkle_set_appcast_url`
  - `win_sparkle_set_eddsa_public_key`
  - `win_sparkle_set_app_details`
  - `win_sparkle_init`
  - `win_sparkle_cleanup`
  - `win_sparkle_check_update_with_ui`
- Initialize WinSparkle only on Windows and only after the first Compose window is visible, so update
  dialogs are attributable to Daemonitor.
- Publish a Windows appcast item for the `.msi` with `sparkle:os="windows-x64"` or the appropriate
  architecture-specific value and `sparkle:installerArguments="/passive"` if the installer flow is
  approved for a reduced-interaction prompt.

Release workflow changes required:

- Authenticode-sign the `.msi` before appcast signing and upload.
- Store the WinSparkle/Sparkle-compatible EdDSA private key outside the repository and never pass it
  on command lines.
- Generate `sparkle:edSignature` for the exact signed `.msi` that will be uploaded.
- Publish appcast XML atomically after the release asset is available.
- Add a Windows updater smoke test that launches an older installed build against a staging appcast,
  verifies the WinSparkle prompt, accepts the update, and confirms the upgraded version starts.

Primary risks:

- JVM/native interop adds shutdown and lifecycle edge cases to a small app that currently has no
  native library runtime dependency.
- Windows update trust needs both installer signing and EdDSA appcast signing. One without the other
  is an incomplete user trust story.
- The appcast cannot be published before all referenced assets exist and are signed, or clients may
  see broken update prompts.

## Shared Appcast And Key Management

A single appcast can serve both Sparkle and WinSparkle if entries use platform attributes such as
`sparkle:os="macos"` and `sparkle:os="windows-x64"`. Keeping one feed reduces release bookkeeping,
but the first implementation should still generate from a deterministic release manifest so each
platform can be validated independently before publication.

Key-management requirements:

- Generate one EdDSA update keypair for appcast/update signatures.
- Commit only public keys and configuration.
- Store private keys in CI secrets or a signing keychain, scoped to release jobs only.
- Avoid command-line private-key flags because process collectors, shell history, and CI logs can
  expose arguments.
- Document key rotation before enabling native updaters for stable users.

## Release Workflow Changes

The native-updater release pipeline should become:

1. Build app images and installers on each target OS.
2. Sign and notarize macOS output; sign Windows output.
3. Smoke-test the signed packaged launchers.
4. Generate appcast metadata for the exact signed artifacts.
5. Upload assets to the GitHub release.
6. Publish appcast XML after assets are reachable.
7. Run staging updater smoke tests from previous signed builds.

The existing `./gradlew test`, packaged-launcher smoke test, and single-asset verification should
remain in place. Native updater checks should be additional release gates, not replacements.

## Decision

Adopt neither framework before Phase 1. After Phase 1, Sparkle 2 is recommended for macOS and
WinSparkle is recommended for Windows only if signing, notarization, appcast generation, and staging
update tests are implemented in CI first. The work should be tracked as a release-infrastructure
epic with two platform-specific implementation slices rather than as a Compose UI feature.

## References

- Sparkle documentation: https://sparkle-project.org/documentation/
- Sparkle publishing updates: https://sparkle-project.github.io/documentation/publishing/
- Sparkle sandboxing and helper signing notes: https://sparkle-project.org/documentation/sandboxing/
- WinSparkle getting started: https://winsparkle.org/guides/getting-started/
- WinSparkle integration guide: https://winsparkle.org/guides/integrating-winsparkle/
- WinSparkle publishing updates: https://winsparkle.org/guides/publishing-updates/
- Compose Multiplatform native distributions: https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html
