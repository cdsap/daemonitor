package io.github.cdsap.daemonitor.collect

import io.github.cdsap.daemonitor.domain.Redactor
import io.github.cdsap.daemonitor.domain.model.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XcodeClassifierTest {

    @Test
    fun classifiesXcodeBuildProcessesAndExcludesGuiAndUnscopedTools() {
        assertEquals(
            ProcessType.XCODE_BUILD,
            GradleProcessClassifier.classify("/usr/bin/xcodebuild -workspace App.xcworkspace -scheme App build"),
        )
        assertEquals(
            ProcessType.XCODE_BUILD_SERVICE,
            GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/SharedFrameworks/SwiftBuild.framework/Versions/A/PlugIns/SWBBuildService.bundle/Contents/MacOS/SWBBuildService"),
        )
        assertEquals(
            ProcessType.XCODE_BUILD_SERVICE,
            GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/SharedFrameworks/XCBuild.framework/Versions/A/PlugIns/XCBBuildService.bundle/Contents/MacOS/XCBBuildService"),
        )
        assertEquals(
            ProcessType.XCODE_COMPILER,
            GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swift-frontend -frontend -c Foo.swift"),
        )
        assertEquals(
            ProcessType.XCODE_COMPILER,
            GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang -x objective-c -c App.m"),
        )
        assertEquals(
            ProcessType.XCODE_TOOL,
            GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/ld -demangle -o App"),
        )
        assertEquals(
            ProcessType.XCODE_TOOL,
            GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/Developer/usr/bin/actool Assets.xcassets"),
        )
        assertNull(GradleProcessClassifier.classify("/Applications/Xcode.app/Contents/MacOS/Xcode"))
        assertNull(GradleProcessClassifier.classify("/usr/bin/clang -o hello hello.c"))
        assertNull(GradleProcessClassifier.classify("/usr/bin/codesign --force --sign - App.app"))
    }

    @Test
    fun extractsXcodeProjectPathWithCwdFallback() {
        assertEquals(
            "/Users/dev/ios-app/App.xcworkspace",
            GradleProcessClassifier.extractXcodeProjectPath(
                "/usr/bin/xcodebuild -workspace App.xcworkspace -scheme App build",
                "/Users/dev/ios-app",
            ),
        )
        assertEquals(
            "/work/Foo/Foo.xcodeproj",
            GradleProcessClassifier.extractXcodeProjectPath(
                "/usr/bin/xcodebuild -project /work/Foo/Foo.xcodeproj build",
                "/Users/dev/ios-app",
            ),
        )
        assertEquals(
            "/Users/dev/ios-app",
            GradleProcessClassifier.extractXcodeProjectPath(
                "/usr/bin/xcodebuild -scheme App build",
                "/Users/dev/ios-app",
            ),
        )
    }

    @Test
    fun redactsXcodeAuthFlagsAndBareSecretSettings() {
        val redacted = Redactor.redactCommandLine(
            "/usr/bin/xcodebuild -workspace App.xcworkspace " +
                "-authenticationKeyPath /Users/dev/AuthKey_ABC.p8 -authenticationKeyID KEY123456 " +
                "API_TOKEN=supersecret SIGNING_KEY=mykey DEVELOPMENT_TEAM=TEAM123",
        )
        assertFalse(redacted.contains("AuthKey_ABC.p8"))
        assertFalse(redacted.contains("KEY123456"))
        assertFalse(redacted.contains("supersecret"))
        assertFalse(redacted.contains("mykey"))
        assertTrue(redacted.contains("API_TOKEN=***"))
        assertTrue(redacted.contains("SIGNING_KEY=***"))
        assertTrue(redacted.contains("DEVELOPMENT_TEAM=TEAM123"))
    }
}
