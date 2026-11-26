package io.github.cdsap.daemonitor.coreipc

internal object GoCoreJson {
    fun extractContainer(json: String, name: String, open: Char, close: Char): String? {
        val match = Regex("\"${Regex.escape(name)}\"\\s*:\\s*").find(json) ?: return null
        val start = match.range.last + 1
        if (start >= json.length || json[start] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                open -> depth++
                close -> if (--depth == 0) return json.substring(start + 1, i)
            }
        }
        return null
    }

    fun extractArray(json: String, name: String): String? =
        extractContainer(json, name, '[', ']')

    fun extractStringArray(json: String, name: String): List<String>? {
        val arrayBody = extractArray(json, name) ?: return null
        val out = mutableListOf<String>()
        var i = 0
        while (i < arrayBody.length) {
            while (i < arrayBody.length && (arrayBody[i].isWhitespace() || arrayBody[i] == ',')) i++
            if (i >= arrayBody.length) break
            if (arrayBody[i] == '"') {
                val (decoded, next) = decodeJsonStringWithEnd(arrayBody, i + 1)
                out += decoded
                i = next
            } else {
                // Skip non-string token (e.g. null) safely without looping forever.
                while (i < arrayBody.length && arrayBody[i] != ',') i++
            }
        }
        return out
    }

    fun splitObjects(arrayBody: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in arrayBody.indices) {
            if (inString) {
                if (escaped) escaped = false else if (arrayBody[i] == '\\') escaped = true else if (arrayBody[i] == '"') inString = false
                continue
            }
            when (arrayBody[i]) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += arrayBody.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    fun stringField(obj: String, name: String): String? {
        val key = "\"$name\""
        var searchFrom = 0
        while (true) {
            val keyIndex = obj.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var i = keyIndex + key.length
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') {
                searchFrom = keyIndex + 1
                continue
            }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            if (obj.startsWith("null", i) &&
                (i + 4 == obj.length || obj[i + 4] in ",}] \r\n\t")
            ) {
                return null
            }
            if (obj[i] != '"') return null
            return decodeJsonString(obj, i + 1)
        }
    }

    /** Linear scan — avoids regex StackOverflowError on multi-KB Gradle command lines. */
    private fun decodeJsonString(source: String, start: Int): String =
        decodeJsonStringWithEnd(source, start).first

    private fun decodeJsonStringWithEnd(source: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = start
        while (i < source.length) {
            when (val c = source[i]) {
                '"' -> return out.toString() to (i + 1)
                '\\' -> {
                    i++
                    if (i >= source.length) break
                    when (val escaped = source[i]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'u' -> if (i + 4 < source.length) {
                            val code = source.substring(i + 1, i + 5).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 4
                            }
                        }
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString() to source.length
    }

    fun numberField(obj: String, name: String): Double? {
        val key = "\"$name\""
        var searchFrom = 0
        while (true) {
            val keyIndex = obj.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var i = keyIndex + key.length
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') {
                searchFrom = keyIndex + 1
                continue
            }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length) return null
            if (obj.startsWith("null", i)) return null
            val start = i
            if (obj[i] == '-') i++
            while (i < obj.length && (obj[i].isDigit() || obj[i] == '.')) i++
            return obj.substring(start, i).toDoubleOrNull()
        }
    }

    fun longField(obj: String, name: String): Long? = numberField(obj, name)?.toLong()

    fun booleanField(obj: String, name: String): Boolean? {
        val key = "\"$name\""
        var searchFrom = 0
        while (true) {
            val keyIndex = obj.indexOf(key, searchFrom)
            if (keyIndex < 0) return null
            var i = keyIndex + key.length
            while (i < obj.length && obj[i].isWhitespace()) i++
            if (i >= obj.length || obj[i] != ':') {
                searchFrom = keyIndex + 1
                continue
            }
            i++
            while (i < obj.length && obj[i].isWhitespace()) i++
            return when {
                obj.startsWith("true", i) -> true
                obj.startsWith("false", i) -> false
                else -> null
            }
        }
    }
}
