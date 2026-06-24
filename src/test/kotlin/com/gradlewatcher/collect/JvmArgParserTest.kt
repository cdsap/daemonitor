package com.gradlewatcher.collect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmArgParserTest {

    @Test
    fun `parses xmx xms and gc`() {
        val args = JvmArgParser.parse("java -Xmx4g -Xms512m -XX:+UseG1GC org.gradle...GradleDaemon")
        assertEquals(4096L, args.maxHeapMb)
        assertEquals(512L, args.minHeapMb)
        assertEquals("G1", args.gc)
    }

    @Test
    fun `null max heap when xmx absent`() {
        val args = JvmArgParser.parse("java -Xms256m org.gradle...GradleDaemon")
        assertNull(args.maxHeapMb)
        assertEquals(256L, args.minHeapMb)
    }

    @Test
    fun `captures gradle daemon flags`() {
        val args = JvmArgParser.parse("java -Dorg.gradle.daemon=true -Dorg.gradle.jvmargs=-Xmx2g Main")
        assertEquals(listOf("-Dorg.gradle.daemon=true", "-Dorg.gradle.jvmargs=-Xmx2g"), args.daemonFlags)
    }
}
