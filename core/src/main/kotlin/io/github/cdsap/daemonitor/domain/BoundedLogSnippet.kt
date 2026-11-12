package io.github.cdsap.daemonitor.domain

internal class BoundedLogSnippet(private val limit: BuildAggregator.LogSnippetLimit) {
    private val lines = ArrayDeque<String>()
    private var chars = 0

    fun append(line: String) {
        val boundedLine = line.takeLast(limit.chars)
        lines.addLast(boundedLine)
        chars += boundedLine.length + if (lines.size > 1) 1 else 0
        while (lines.size > limit.lines || chars > limit.chars) {
            val removed = lines.removeFirst()
            chars -= removed.length + if (lines.isNotEmpty()) 1 else 0
        }
    }

    fun value(): String? = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
