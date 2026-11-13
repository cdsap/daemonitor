# Gradle plugin release smoke

Publish `io.github.cdsap.daemonitor` from `:gradle-plugin` (currently **0.1.0**) whenever a
product tag ships plugin changes. The artifact line is independent of app/cored `1.2.x`, but
**cutting a Daemonitor GitHub Release must include a deliberate plugin version decision**
(bump in `gradle-plugin/build.gradle.kts` when the plugin changed; leave unchanged only when
the portal already has that version and no plugin delta shipped).

## Automated publish

1. Tag `vX.Y.Z` → `.github/workflows/release.yml` builds native assets, then job
   `publish-gradle-plugin` runs `:gradle-plugin:publishPlugins` using
   `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`.
2. Backfill / republish from an existing tag:
   Actions → **Publish Gradle plugin** → Run workflow → ref `vX.Y.Z`.

Reject SNAPSHOT versions; Portal rejects re-publishing an identical version.

## Manual smoke (before or right after Portal publish)

Exercises real `daemonitor-cored` matching the product tag and pinned Develocity Gradle plugin
**4.6.1**.

1. Start cored with a temporary socket and database:

   ```sh
   daemonitor-cored --socket "$TMPDIR/daemonitor-core.sock" --db "$TMPDIR/daemonitor.sqlite"
   ```

2. Run a disposable Gradle project with the released plugin and the pinned
   Develocity plugin:

   ```sh
   ./gradlew \
     -Pdaemonitor.cored.socket="$TMPDIR/daemonitor-core.sock" \
     -Dorg.gradle.jvmargs=-Xmx512m \
     --no-daemon clean help
   ```

3. Verify all three views came from the same close:

   - `build/daemonitor/observation-summary.json` exists and contains the expected
     process rows and RSS peaks.
   - The Develocity build contains the `gbos.v1.producer.daemonitor` values for
     those same process IDs and peaks.
   - MCP `daemonitor_search_history` returns one retained observation session with
     the same process rows and total peak RSS.

Also run the failure matrix before publication: with cored stopped, the build
continues with a warning and no report or GBOS values; with cored close returning
an error, the same is true; with Develocity absent, the local report and MCP
retention remain available while no GBOS publication is claimed.
