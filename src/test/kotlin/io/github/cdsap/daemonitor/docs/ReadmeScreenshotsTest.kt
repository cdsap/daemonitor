package io.github.cdsap.daemonitor.docs

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadmeScreenshotsTest {
    private val readme = Files.readString(Path.of("README.md"))

    @Test
    fun `README screenshots are native-size PNGs with meaningful alt text`() {
        val screenshots = mapOf(
            "docs/images/live-monitor.png" to "Daemonitor Live monitor showing active Gradle processes, metrics, status badges, and process details",
            "docs/images/build-history.png" to "Daemonitor build history showing status and source tags, agent attribution, metrics, and build details",
        )

        screenshots.forEach { (relativePath, altText) ->
            val path = Path.of(relativePath)
            assertTrue(Files.isRegularFile(path), "$relativePath should be checked in")
            val image: BufferedImage = assertNotNull(ImageIO.read(path.toFile()), "$relativePath should be a readable image")
            assertEquals(1180, image.width)
            assertEquals(760, image.height)
            assertTrue(readme.contains("![$altText]($relativePath)"), "$relativePath should be embedded with meaningful alt text")
        }
    }

    @Test
    fun `sample screenshot data uses only synthetic workspace paths`() {
        val states = listOf(SampleUi.liveState(), SampleUi.historyState())
        val renderedData = states.toString()
        assertTrue(renderedData.contains("/workspace/samples/"))
        assertTrue("/Users/" !in renderedData)
        assertTrue("/home/" !in renderedData)
        assertTrue("token=" !in renderedData.lowercase())
        assertTrue("password=" !in renderedData.lowercase())
    }
}
