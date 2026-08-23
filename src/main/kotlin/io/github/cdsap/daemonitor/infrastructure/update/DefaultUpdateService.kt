package io.github.cdsap.daemonitor.infrastructure.update

import io.github.cdsap.daemonitor.BuildInfo
import io.github.cdsap.daemonitor.application.platform.ProcessExiter
import io.github.cdsap.daemonitor.application.platform.UrlOpener
import io.github.cdsap.daemonitor.application.update.ApplyUpdate
import io.github.cdsap.daemonitor.application.update.CheckForUpdate
import io.github.cdsap.daemonitor.application.update.PrepareUpdate
import io.github.cdsap.daemonitor.application.update.UpdateService
import io.github.cdsap.daemonitor.application.update.UpdateSource
import io.github.cdsap.daemonitor.infrastructure.platform.DesktopUrlOpener
import io.github.cdsap.daemonitor.infrastructure.platform.SystemProcessExiter
import io.github.cdsap.daemonitor.update.DesktopUpdateApplier
import io.github.cdsap.daemonitor.update.DesktopUpdateInstaller
import io.github.cdsap.daemonitor.update.GitHubReleaseUpdateSource
import io.github.cdsap.daemonitor.update.UpdateApplier
import io.github.cdsap.daemonitor.update.UpdateInstaller

/**
 * Wires desktop/GitHub update infrastructure into [UpdateService].
 * Composition root for the update feature until a broader app container exists.
 */
fun defaultUpdateService(
    source: UpdateSource = GitHubReleaseUpdateSource(),
    installer: UpdateInstaller = DesktopUpdateInstaller(),
    applier: UpdateApplier = DesktopUpdateApplier(),
    urlOpener: UrlOpener = DesktopUrlOpener(),
    processExiter: ProcessExiter = SystemProcessExiter(),
    currentVersion: () -> String = { BuildInfo.current.version },
): UpdateService = UpdateService(
    checkForUpdate = CheckForUpdate(source = source, currentVersion = currentVersion),
    prepareUpdate = PrepareUpdate(installer),
    applyUpdate = ApplyUpdate(applier = applier, processExiter = processExiter),
    urlOpener = urlOpener,
)
