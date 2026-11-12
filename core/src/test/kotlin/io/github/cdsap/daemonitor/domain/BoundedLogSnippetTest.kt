package io.github.cdsap.daemonitor.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedLogSnippetTest {
    @Test
    fun `line limit retains newest lines in order`() {
        val snippet = BoundedLogSnippet(BuildAggregator.LogSnippetLimit(lines = 2, chars = 100))
        listOf("first", "second", "third").forEach(snippet::append)
        assertEquals("second\nthird", snippet.value())
    }

    @Test
    fun `character limit includes newlines and evicts oldest lines`() {
        val snippet = BoundedLogSnippet(BuildAggregator.LogSnippetLimit(lines = 5, chars = 5))
        snippet.append("aa")
        snippet.append("bb")
        assertEquals("aa\nbb", snippet.value())
        snippet.append("c")
        assertEquals("bb\nc", snippet.value())
        snippet.append("dd")
        assertEquals("c\ndd", snippet.value())
    }

    @Test
    fun `oversized line keeps its tail and evicts previous lines`() {
        val snippet = BoundedLogSnippet(BuildAggregator.LogSnippetLimit(lines = 3, chars = 5))
        snippet.append("old")
        snippet.append("123456789")
        assertEquals("56789", snippet.value())
    }

    @Test
    fun `empty buffer is null but an empty line is retained`() {
        val snippet = BoundedLogSnippet(BuildAggregator.LogSnippetLimit(lines = 2, chars = 5))
        assertNull(snippet.value())
        snippet.append("")
        assertEquals("", snippet.value())
    }

    @Test
    fun `zero limits preserve empty line and eviction behavior`() {
        val noLines = BoundedLogSnippet(BuildAggregator.LogSnippetLimit(lines = 0, chars = 5))
        noLines.append("text")
        assertNull(noLines.value())

        val noChars = BoundedLogSnippet(BuildAggregator.LogSnippetLimit(lines = 2, chars = 0))
        noChars.append("text")
        noChars.append("more")
        assertEquals("", noChars.value())
    }
}
