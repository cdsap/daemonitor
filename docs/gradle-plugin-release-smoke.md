# Gradle plugin 0.1.0 release smoke

Run this smoke when publishing `io.github.cdsap.daemonitor` 0.1.0. It exercises the
real `daemonitor-cored` 1.2.4 process and the pinned Develocity Gradle plugin 4.6.1.

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
