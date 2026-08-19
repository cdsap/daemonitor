package io.github.cdsap.daemonitor.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicWebsiteTest {
    private val siteRoot = Path.of("site")
    private val indexHtml = Files.readString(siteRoot.resolve("index.html"))
    private val stylesCss = Files.readString(siteRoot.resolve("styles.css"))
    private val appJs = Files.readString(siteRoot.resolve("app.js"))
    private val pagesWorkflow = Files.readString(Path.of(".github/workflows/pages.yml"))
    private val readme = Files.readString(Path.of("README.md"))

    @Test
    fun `landing page files and screenshots are checked in`() {
        listOf(
            "index.html",
            "styles.css",
            "app.js",
            "assets/favicon.png",
            "assets/icon-192.png",
            "assets/live-monitor.png",
            "assets/process-visual.png",
            "assets/build-history.png",
        ).forEach { relativePath ->
            val path = siteRoot.resolve(relativePath)
            assertTrue(Files.isRegularFile(path), "$relativePath should exist under site/")
            assertTrue(Files.size(path) > 0L, "$relativePath should not be empty")
        }

        listOf(
            "live-monitor.png",
            "process-visual.png",
            "build-history.png",
        ).forEach { filename ->
            val docsImage = Path.of("docs/images").resolve(filename)
            val siteImage = siteRoot.resolve("assets").resolve(filename)
            assertTrue(
                Files.mismatch(docsImage, siteImage) == -1L,
                "site/assets/$filename should match docs/images/$filename",
            )
        }
    }

    @Test
    fun `landing page covers hero features privacy download and metadata`() {
        listOf(
            "Daemonitor — Activity Monitor for Gradle and Kotlin daemons",
            "meta name=\"description\"",
            "property=\"og:title\"",
            "property=\"og:description\"",
            "property=\"og:image\"",
            "rel=\"icon\"",
            "Activity Monitor for your Gradle and Kotlin daemons",
            "local Activity Monitor for Gradle and Kotlin daemons",
            "Download Daemonitor",
            "View on GitHub",
            "macOS · Windows · Linux",
            "id=\"features\"",
            "Live Monitor",
            "Build History",
            "MCP / Agent workflows",
            "id=\"privacy\"",
            "Your build data stays on your machine.",
            "id=\"download\"",
            "https://github.com/cdsap/daemonitor/releases/latest",
            "assets/live-monitor.png",
            "assets/process-visual.png",
            "assets/build-history.png",
            "MIT License",
        ).forEach { required ->
            assertTrue(indexHtml.contains(required), "index.html should include: $required")
        }

        assertTrue(
            indexHtml.contains("alt=\"Daemonitor Live Monitor"),
            "Live Monitor screenshot should have meaningful alt text",
        )
        assertTrue(
            indexHtml.contains("alt=\"Daemonitor Visual tab"),
            "Visual screenshot should have meaningful alt text",
        )
        assertTrue(
            indexHtml.contains("alt=\"Daemonitor Build History"),
            "Build History screenshot should have meaningful alt text",
        )
    }

    @Test
    fun `download links do not hardcode a release version`() {
        val versionPattern = Regex("""Daemonitor-\d+\.\d+\.\d+""")
        assertFalse(
            versionPattern.containsMatchIn(indexHtml),
            "Landing page must not hardcode a Daemonitor release version",
        )
        assertFalse(
            versionPattern.containsMatchIn(appJs),
            "Landing page JavaScript must not hardcode a Daemonitor release version",
        )
        assertTrue(
            indexHtml.contains("href=\"https://github.com/cdsap/daemonitor/releases/latest\""),
            "Download CTAs should point at the latest GitHub Release",
        )
    }

    @Test
    fun `site supports themes reduced motion and avoids third-party tracking`() {
        assertTrue(stylesCss.contains("prefers-color-scheme"))
        assertTrue(stylesCss.contains("prefers-reduced-motion"))
        assertTrue(stylesCss.contains("system-ui"))

        listOf(
            "googletagmanager",
            "google-analytics",
            "gtag(",
            "analytics.js",
            "plausible.io",
            "segment.com",
            "hotjar",
            "facebook.net",
        ).forEach { tracker ->
            assertFalse(
                indexHtml.lowercase().contains(tracker),
                "Landing page must not include tracker reference: $tracker",
            )
            assertFalse(
                appJs.lowercase().contains(tracker),
                "Landing page JavaScript must not include tracker reference: $tracker",
            )
        }
    }

    @Test
    fun `pages workflow deploys the static site from main`() {
        listOf(
            "name: Deploy GitHub Pages",
            "branches: [main]",
            "workflow_dispatch:",
            "pages: write",
            "id-token: write",
            "actions/upload-pages-artifact@v3",
            "actions/deploy-pages@v4",
            "name: github-pages",
            "page_url",
            "cp docs/images/live-monitor.png",
        ).forEach { required ->
            assertTrue(pagesWorkflow.contains(required), "pages.yml should include: $required")
        }
    }

    @Test
    fun `README links to the public website`() {
        assertTrue(
            readme.contains("https://cdsap.github.io/daemonitor/"),
            "README should link to the GitHub Pages site",
        )
    }

    @Test
    fun `asset references stay relative for the repository Pages subpath`() {
        assertFalse(indexHtml.contains("href=\"/"), "Absolute root hrefs break under /daemonitor/")
        assertFalse(indexHtml.contains("src=\"/"), "Absolute root src paths break under /daemonitor/")
        assertTrue(indexHtml.contains("href=\"styles.css\""))
        assertTrue(indexHtml.contains("src=\"app.js\""))
        assertTrue(indexHtml.contains("href=\"assets/favicon.png\""))
    }
}
